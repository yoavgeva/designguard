package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Catch-all handlers that swallow the error: a {@code catch} of {@code Exception}, {@code RuntimeException} or
 * {@code Throwable} that only logs, and returns a default, as {@code null}, a literal, a variable or an empty collection,
 * or nothing. A handler that does anything else with the failure, such as passing it to a call other than a log or
 * throwing, passes, and so do these:
 *
 * <ul>
 *   <li>in a loop, or a lambda given to {@code forEach}, {@code map} and the like, which keeps one failing item from
 *       stopping the rest;</li>
 *   <li>around releasing a resource, such as {@code close()}, which leaves nothing to recover;</li>
 *   <li>in a method named for it, as {@code deleteQuietly} and {@code tryParse} are; a predicate, as {@code isValid}
 *       is, whose {@code false} answers it; one that releases something, as {@code close()} and {@code dispose()} do;
 *       or one with nobody to tell, a thread's {@code run()}, {@code main}, or an initializer;</li>
 *   <li>returning the default the caller passed, as a {@code defaultValue} parameter, or {@code false} where the
 *       method otherwise returns {@code true}, which tells the caller it failed.</li>
 * </ul>
 */
final class CatchAllHandlers {

    private static final Set<String> CATCH_ALL = Set.of("Exception", "RuntimeException", "Throwable",
            "java.lang.Exception", "java.lang.RuntimeException", "java.lang.Throwable");
    private static final Set<String> RELEASE_CALLS = Set.of("close", "shutdown", "shutdownNow", "stop", "disconnect",
            "release", "cancel", "unlock", "delete", "deleteIfExists");
    /** Methods that call the lambda they are given once per item. */
    private static final Set<String> PER_ITEM = Set.of("forEach", "forEachOrdered", "forEachRemaining", "map", "flatMap",
            "filter", "anyMatch", "allMatch", "noneMatch", "removeIf", "replaceAll", "computeIfAbsent", "comparing");
    private static final Pattern TRYING = Pattern.compile("try[A-Z].*|.*(?i:quietly|silently).*");
    private static final Pattern DEFAULT = Pattern.compile("(?i).*(default|fallback).*");
    private static final Pattern PREDICATE = Pattern.compile("(is|has|can|exists|contains|should|supports|matches)([A-Z].*)?");
    private static final Set<String> RELEASING_METHODS = Set.of("close", "dispose", "destroy", "shutdown", "stop",
            "release", "cleanup", "cleanUp", "disconnect", "preDestroy");
    private static final String CARRIES_ON = "carries on";

    private CatchAllHandlers() {
    }

    /** How a catch-all handler carries on, as "returns null" or "carries on", when it swallows the error. */
    static Optional<String> swallowed(CatchClause handler) {
        String caught = handler.getParameter().getNameAsString();
        List<Statement> statements = handler.getBody().getStatements();
        if (!catchesAll(handler) || insideLoop(handler) || onlyReleases(handler) || meansTo(handler)
                || handler.getBody().findFirst(ThrowStmt.class).isPresent()) {
            return Optional.empty();
        }
        Optional<ReturnStmt> returned = statements.isEmpty() ? Optional.empty()
                : Optional.of(statements.get(statements.size() - 1)).filter(Statement::isReturnStmt).map(Statement::asReturnStmt);
        List<Statement> before = returned.isPresent() ? statements.subList(0, statements.size() - 1) : statements;
        if (!before.stream().allMatch(statement -> DefaultValues.isLog(statement, caught))) {
            return Optional.empty();
        }
        return returned.map(statement -> statement.getExpression()
                        .map(value -> DefaultValues.isDefault(value, caught) && !tellsTheCaller(handler, value)
                                ? Optional.of("returns " + value) : Optional.<String>empty())
                        .orElse(Optional.of(CARRIES_ON)))
                .orElse(Optional.of(CARRIES_ON));
    }

    private static boolean catchesAll(CatchClause handler) {
        Type type = handler.getParameter().getType();
        Stream<String> types = type.isUnionType()
                ? type.asUnionType().getElements().stream().map(Object::toString)
                : Stream.of(type.asString());
        return types.anyMatch(CATCH_ALL::contains);
    }

    /**
     * Whether the handler sits in a loop's body, or a lambda given to a method that calls it once per item, keeping one
     * failing item from stopping the rest.
     */
    private static boolean insideLoop(Node node) {
        return Stream.<Class<? extends Node>>of(ForStmt.class, ForEachStmt.class, WhileStmt.class, DoStmt.class)
                .anyMatch(loop -> Scopes.nearest(node, loop)
                        .filter(found -> Scopes.enclosingCallable(found).equals(Scopes.enclosingCallable(node)))
                        .isPresent())
                || Scopes.nearest(node, LambdaExpr.class)
                        .flatMap(Node::getParentNode)
                        .filter(parent -> parent instanceof MethodCallExpr call && PER_ITEM.contains(call.getNameAsString()))
                        .isPresent();
    }

    /** Whether the try block only releases something, as closing a resource does. */
    private static boolean onlyReleases(CatchClause handler) {
        return handler.getParentNode().filter(TryStmt.class::isInstance).map(TryStmt.class::cast)
                .map(statement -> !statement.getTryBlock().isEmpty() && statement.getTryBlock().getStatements().stream()
                        .allMatch(inner -> inner.isExpressionStmt()
                                && inner.asExpressionStmt().getExpression() instanceof MethodCallExpr call
                                && RELEASE_CALLS.contains(call.getNameAsString())))
                .orElse(false);
    }

    /**
     * Whether the method means to swallow it: named for it as {@code deleteQuietly} and {@code tryParse} are, a
     * predicate, one that releases something, or one with nobody to tell, a thread's {@code run()}, {@code main} or an
     * initializer.
     */
    private static boolean meansTo(CatchClause handler) {
        Optional<CallableDeclaration<?>> callable = Scopes.enclosingCallable(handler);
        return callable.isEmpty() || callable
                .filter(found -> TRYING.matcher(found.getNameAsString()).matches()
                        || RELEASING_METHODS.contains(found.getNameAsString())
                        || "main".equals(found.getNameAsString())
                        || "run".equals(found.getNameAsString()) && found.getParameters().isEmpty()
                        || isPredicate(found))
                .isPresent();
    }

    /** A method named as a question with a boolean answer, as {@code isValid} and {@code exists} are. */
    private static boolean isPredicate(CallableDeclaration<?> callable) {
        return callable instanceof MethodDeclaration method
                && PREDICATE.matcher(method.getNameAsString()).matches()
                && Set.of("boolean", "Boolean").contains(method.getType().asString());
    }

    /**
     * Whether the value tells the caller: the default it passed, as a {@code defaultValue} parameter, or {@code false}
     * where the method otherwise returns {@code true}.
     */
    private static boolean tellsTheCaller(CatchClause handler, Expression value) {
        Optional<CallableDeclaration<?>> callable = Scopes.enclosingCallable(handler);
        return value instanceof NameExpr name && DEFAULT.matcher(name.getNameAsString()).matches()
                        && callable.filter(found -> found.getParameterByName(name.getNameAsString()).isPresent()).isPresent()
                || value.isBooleanLiteralExpr() && !value.asBooleanLiteralExpr().getValue()
                        && callable.filter(found -> found.findAll(ReturnStmt.class).stream()
                                .anyMatch(other -> other.getExpression().filter(Expression::isBooleanLiteralExpr)
                                        .filter(literal -> literal.asBooleanLiteralExpr().getValue()).isPresent()))
                                .isPresent();
    }
}
