package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ThrowStmt;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds {@code throw new UnsupportedOperationException()} placeholders. Overrides that deliberately refuse an
 * operation, like a read-only {@code Iterator.remove()}, are allowed unless their message says "not implemented".
 */
final class PlaceholderThrows implements SourceFinder {

    private static final Pattern NOT_IMPLEMENTED = Pattern.compile("(?i)not (yet )?implemented|\\btodo\\b");

    @Override
    public Stream<Violation> find(ParsedFile file, ClassFilter filter) {
        return file.unit().findAll(ThrowStmt.class).stream()
                .filter(PlaceholderThrows::isPlaceholder)
                .filter(stmt -> Scopes.selected(filter, file, stmt))
                .map(stmt -> Scopes.violation(StubCheck.RULE, file, stmt,
                        ownerName(stmt) + " is a stub: it throws UnsupportedOperationException",
                        "implement it, or delete the method until it is needed"));
    }

    private static boolean isPlaceholder(ThrowStmt stmt) {
        if (!(stmt.getExpression() instanceof ObjectCreationExpr creation)
                || !creation.getType().getNameAsString().endsWith("UnsupportedOperationException")) {
            return false;
        }
        boolean saysNotImplemented = creation.getArguments().stream()
                .filter(StringLiteralExpr.class::isInstance)
                .map(argument -> ((StringLiteralExpr) argument).getValue())
                .anyMatch(message -> NOT_IMPLEMENTED.matcher(message).find());
        boolean inOverride = Scopes.enclosingCallable(stmt)
                .map(callable -> Scopes.hasAnnotation(callable, "Override"))
                .orElse(false);
        return saysNotImplemented || !inOverride;
    }

    private static String ownerName(ThrowStmt stmt) {
        return Scopes.enclosingCallable(stmt)
                .map(Scopes::describe)
                .or(() -> Scopes.enclosingType(stmt).map(type -> type.getNameAsString()))
                .orElse("code");
    }
}
