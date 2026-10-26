package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import java.util.List;
import java.util.Map;

/**
 * Reactor's and {@code CompletableFuture}'s operators that turn every error into a value: {@code onErrorReturn(value)},
 * {@code onErrorComplete()}, and {@code onErrorResume} and {@code exceptionally} with a lambda that returns a default.
 * Their forms that take the error type to handle pass.
 */
final class ErrorFallbacks {

    /** The operators, by the argument count of their catch-everything form. */
    private static final Map<String, Integer> FALLBACKS = Map.of("onErrorReturn", 1, "onErrorComplete", 0,
            "onErrorResume", 1, "exceptionally", 1);

    private ErrorFallbacks() {
    }

    /** {@code onErrorReturn(value)}, {@code onErrorResume(e -> Mono.empty())}, {@code exceptionally(e -> null)}. */
    static boolean isFallback(MethodCallExpr call) {
        Integer arguments = FALLBACKS.get(call.getNameAsString());
        if (arguments == null || call.getArguments().size() != arguments || call.getScope().isEmpty()) {
            return false;
        }
        return arguments == 0 || "onErrorReturn".equals(call.getNameAsString())
                || call.getArgument(0) instanceof LambdaExpr lambda && returnsDefault(lambda);
    }

    private static boolean returnsDefault(LambdaExpr lambda) {
        String caught = lambda.getParameters().isEmpty() ? "" : lambda.getParameter(0).getNameAsString();
        Statement body = lambda.getBody();
        if (body.findFirst(ThrowStmt.class).isPresent()) {
            return false;
        }
        if (body instanceof ExpressionStmt expression) {
            return DefaultValues.isDefault(expression.getExpression(), caught);
        }
        List<Statement> statements = body.asBlockStmt().getStatements();
        return !statements.isEmpty() && statements.get(statements.size() - 1) instanceof ReturnStmt last
                && last.getExpression().filter(value -> DefaultValues.isDefault(value, caught)).isPresent()
                && statements.subList(0, statements.size() - 1).stream()
                        .allMatch(statement -> DefaultValues.isLog(statement, caught));
    }
}
