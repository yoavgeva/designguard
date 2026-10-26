package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds errors swallowed: a handler that catches everything and carries on as if the call worked, so its callers
 * can't tell it failed.
 *
 * <ul>
 *   <li>A {@code catch} of {@code Exception}, {@code RuntimeException} or {@code Throwable} that only logs, and
 *       returns a default or nothing; see {@link CatchAllHandlers}.</li>
 *   <li>Reactor's and {@code CompletableFuture}'s operators that turn every error into a default; see
 *       {@link ErrorFallbacks}.</li>
 * </ul>
 */
public final class SwallowedErrorsCheck {

    private static final String RULE = "no-swallowed-errors";
    private static final String CATCH_FIX = "catch only the exceptions you expect and let the rest propagate, or "
            + "rethrow it wrapped in an unchecked exception";

    /**
     * Finds swallowed errors in the sources the filter selects.
     *
     * @param sources the parsed sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .flatMap(file -> Stream.concat(
                        file.unit().findAll(CatchClause.class).stream()
                                .filter(handler -> Scopes.selected(filter, file, handler))
                                .flatMap(handler -> CatchAllHandlers.swallowed(handler).map(returns -> Scopes.violation(
                                        RULE, file, handler, where(handler) + " catches every exception and " + returns
                                                + ", so callers can't tell it failed", CATCH_FIX)).stream()),
                        file.unit().findAll(MethodCallExpr.class).stream()
                                .filter(call -> Scopes.selected(filter, file, call) && ErrorFallbacks.isFallback(call))
                                .map(call -> fallback(file, call))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Violation fallback(ParsedFile file, MethodCallExpr call) {
        boolean future = "exceptionally".equals(call.getNameAsString());
        return Scopes.violation(RULE, file, call, where(call) + " turns every error into a fallback with "
                        + call.getNameAsString() + ", so " + (future ? "callers" : "subscribers") + " can't tell it failed",
                future ? "handle only the exceptions you expect in exceptionally, and rethrow the rest"
                        : "handle only the errors you expect, as onErrorResume(NotFoundException.class, ...) does, and let "
                                + "the rest through");
    }

    private static String where(Node node) {
        return Scopes.enclosingCallable(node).map(Scopes::describe)
                .orElseGet(() -> Scopes.enclosingType(node).map(type -> type.getNameAsString() + "'s initializer").orElse(""));
    }
}
