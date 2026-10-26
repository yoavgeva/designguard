package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.Locale;
import java.util.Set;

/** What an error handler does with the error it caught: only reports it, or returns a default in its place. */
final class DefaultValues {

    private static final Set<String> EMPTY_FACTORIES = Set.of("of", "empty", "emptyList", "emptyMap", "emptySet", "just");

    private DefaultValues() {
    }

    /** A statement that only reports the error: a call on a logger, {@code printStackTrace()}, or console output. */
    static boolean isLog(Statement statement, String caught) {
        if (!(statement instanceof ExpressionStmt expression) || !(expression.getExpression() instanceof MethodCallExpr call)) {
            return false;
        }
        String receiver = call.getScope().map(Expression::toString).orElse("").toLowerCase(Locale.ROOT);
        return receiver.contains("log") || receiver.startsWith("system.")
                || "printStackTrace".equals(call.getNameAsString()) && receiver.equals(caught.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a value is a default rather than something made from the error: {@code null}, a literal, a variable
     * other than the caught one, or an empty collection or {@code Optional}.
     */
    static boolean isDefault(Expression value, String caught) {
        return value instanceof LiteralExpr
                || value instanceof NameExpr name && !name.getNameAsString().equals(caught)
                || value instanceof MethodCallExpr call && EMPTY_FACTORIES.contains(call.getNameAsString())
                        && call.getArguments().stream().allMatch(argument -> isDefault(argument, caught))
                        && call.getScope().isPresent()
                || value.isObjectCreationExpr() && value.asObjectCreationExpr().getArguments().isEmpty();
    }
}
