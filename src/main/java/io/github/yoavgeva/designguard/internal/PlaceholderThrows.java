package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds placeholder throws. A throw is one if its message says the code is unfinished, as "not implemented yet" or
 * "TODO" do, or it throws a {@code NotImplementedException}. A {@code throw new UnsupportedOperationException()} is
 * one if it is the whole body and nothing says the refusal is deliberate: a message, a Javadoc that says the method
 * is unsupported, {@code @Deprecated}, a private constructor, or a hook such as an override, which may refuse an
 * operation like a read-only {@code Iterator.remove()}, or a method a subclass overrides.
 */
final class PlaceholderThrows implements SourceFinder {

    /** "Not implemented" unless it says for what it isn't, as "not implemented for AnyType" does, which refuses. */
    private static final Pattern NOT_IMPLEMENTED =
            Pattern.compile("(?i)not yet implemented|not implemented(?! (for|by|in|on)\\b)|\\btodo\\b");
    private static final Pattern UNSUPPORTED = Pattern.compile("(?i)unsupported|not supported|always throws");
    private static final String REFUSAL = "UnsupportedOperationException";

    private final String rule;
    private final OverriddenMethods overridden;

    /**
     * Configures the finder.
     *
     * @param rule       the rule id its violations carry
     * @param overridden the methods the project's subclasses override
     */
    PlaceholderThrows(String rule, OverriddenMethods overridden) {
        this.rule = rule;
        this.overridden = overridden;
    }

    @Override
    public Stream<Violation> find(ParsedFile file, ClassFilter filter) {
        return file.unit().findAll(ThrowStmt.class).stream()
                .filter(stmt -> stmt.getExpression() instanceof ObjectCreationExpr creation && isPlaceholder(stmt, creation))
                .filter(stmt -> Scopes.selected(filter, file, stmt))
                .map(stmt -> Scopes.violation(rule, file, stmt,
                        ownerName(stmt) + " is a stub: it throws "
                                + ((ObjectCreationExpr) stmt.getExpression()).getType().getNameAsString(),
                        "implement it, or delete the method until it is needed"));
    }

    private boolean isPlaceholder(ThrowStmt stmt, ObjectCreationExpr creation) {
        String type = creation.getType().getNameAsString();
        boolean saysNotImplemented = creation.getArguments().stream()
                .filter(StringLiteralExpr.class::isInstance)
                .map(argument -> ((StringLiteralExpr) argument).getValue())
                .anyMatch(message -> NOT_IMPLEMENTED.matcher(message).find());
        if (saysNotImplemented || type.endsWith("NotImplementedException")) {
            return true;
        }
        Optional<CallableDeclaration<?>> callable = Scopes.enclosingCallable(stmt);
        return type.endsWith(REFUSAL) && creation.getArguments().isEmpty() && callable.isPresent()
                && isWholeBody(callable.get(), stmt) && !isDeliberate(callable.get());
    }

    private static boolean isWholeBody(CallableDeclaration<?> callable, ThrowStmt stmt) {
        Optional<BlockStmt> body = callable instanceof MethodDeclaration method ? method.getBody()
                : Optional.of(((ConstructorDeclaration) callable).getBody());
        return body.filter(block -> block.getStatements().size() == 1 && block.getStatement(0).equals(stmt)).isPresent();
    }

    private boolean isDeliberate(CallableDeclaration<?> callable) {
        return Scopes.hasAnnotation(callable, "Deprecated") || Hooks.javadocSays(callable, UNSUPPORTED)
                || callable instanceof ConstructorDeclaration && callable.isPrivate() || Hooks.isHook(callable, overridden);
    }

    private static String ownerName(ThrowStmt stmt) {
        return Scopes.enclosingCallable(stmt)
                .map(Scopes::describe)
                .or(() -> Scopes.enclosingType(stmt).map(type -> type.getNameAsString()))
                .orElse("code");
    }
}
