package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds tests with logic ("conditional test logic"): an {@code if} or a {@code switch} in a test method, which makes it
 * check different things on different runs, or a loop around an assertion, which hides which case failed. A loop
 * that only sets up data is fine, and so is an {@code if} inside it that shapes the data, one that repeats a step, an
 * {@code if} that only fails, which is an assertion written by hand, and logic in helper methods, in classes a test
 * declares, such as a fake, and in lambdas that assert nothing, which are fakes too; the tests themselves take one
 * path.
 */
public final class BranchingTestsCheck {

    private static final String RULE = "tests-have-no-logic";
    private static final String FAIL = "fail";
    /** What the names of the errors a test throws to fail end with, as {@code AssertionError} and opentest4j's do. */
    private static final String ERROR_SUFFIX = "Error";
    private static final List<Class<? extends Node>> LOOPS =
            List.of(ForStmt.class, ForEachStmt.class, WhileStmt.class, DoStmt.class);
    private static final List<Map.Entry<Class<? extends Node>, String>> LOGIC = List.of(
            Map.entry(IfStmt.class, "an if"), Map.entry(SwitchStmt.class, "a switch"), Map.entry(SwitchExpr.class, "a switch"),
            Map.entry(ForStmt.class, "a for loop"), Map.entry(ForEachStmt.class, "a for loop"),
            Map.entry(WhileStmt.class, "a while loop"), Map.entry(DoStmt.class, "a do loop"));

    /**
     * Finds violations in the test sources the filter selects.
     *
     * @param sources the parsed test sources
     * @param filter  which classes to check
     * @return violations ordered by file and line, at most one per test
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .flatMap(file -> file.unit().findAll(MethodDeclaration.class).stream()
                        .filter(method -> UnitTestMethods.isTest(method) && Scopes.selected(filter, file, method))
                        .flatMap(test -> firstLogic(test).map(logic -> Scopes.violation(RULE, file, logic.getKey(),
                                "test " + Scopes.describe(test) + " has " + logic.getValue() + ": a test should take one path",
                                "split it into one test per case, or make the cases parameters of a @ParameterizedTest"))
                                .stream()))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /**
     * The first branch, or loop around an assertion, in the test, and what kind it is. Code of a class the test
     * declares, such as a fake, runs when the code under test calls it, so it isn't the test's path.
     */
    private static Optional<Map.Entry<Node, String>> firstLogic(MethodDeclaration test) {
        return test.findAll(Node.class).stream()
                .flatMap(node -> LOGIC.stream().filter(kind -> kind.getKey().isInstance(node))
                        .map(kind -> Map.entry(node, kind.getValue())))
                .filter(found -> !inClassOf(test, found.getKey())
                        && !(found.getKey() instanceof IfStmt branch
                                && (onlyFails(test, branch) || shapesSetUpData(test, branch))))
                .filter(found -> LOOPS.stream().noneMatch(loop -> loop.isInstance(found.getKey()))
                        || asserts(found.getKey()) && !(found.getKey() instanceof ForStmt loop && isRepetition(loop)))
                .min(Comparator.comparingInt(found -> Scopes.line(found.getKey())));
    }

    /**
     * Whether the {@code if} is an assertion written by hand: it has no {@code else}, all it does is fail, as
     * {@code if (size != 2) { fail("got " + size); }} and {@code throw new AssertionError(...)} do, and it tests what the
     * test observed, not which case runs, as {@code if (shouldFail)} on a parameter does. It checks the same thing on
     * every run.
     */
    private static boolean onlyFails(MethodDeclaration test, IfStmt branch) {
        Statement then = branch.getThenStmt();
        List<Statement> statements = then.isBlockStmt() ? then.asBlockStmt().getStatements() : List.of(then);
        Set<String> parameters = test.getParameters().stream().map(Parameter::getNameAsString).collect(Collectors.toSet());
        return branch.getElseStmt().isEmpty() && !statements.isEmpty()
                && branch.getCondition().findAll(NameExpr.class).stream()
                        .noneMatch(name -> parameters.contains(name.getNameAsString()))
                && statements.stream().allMatch(statement ->
                statement.isExpressionStmt() && statement.asExpressionStmt().getExpression() instanceof MethodCallExpr call
                        && FAIL.equals(call.getNameAsString())
                || statement.isThrowStmt() && statement.asThrowStmt().getExpression() instanceof ObjectCreationExpr error
                        && error.getType().getNameAsString().endsWith(ERROR_SUFFIX));
    }

    /**
     * Whether the {@code if} shapes the data a loop sets up, as {@code if (i > 0) json.append(',')} does: it checks
     * nothing, and neither does any loop around it, which only sets up data. A {@code throw} checks, as
     * {@code if (info.isResource()) throw new IllegalStateException(...)} in a loop over cases does.
     */
    private static boolean shapesSetUpData(MethodDeclaration test, IfStmt branch) {
        List<Node> loops = Stream.iterate(branch.getParentNode(), Optional::isPresent, node -> node.flatMap(Node::getParentNode))
                .map(Optional::get)
                .takeWhile(node -> !node.equals(test))
                .filter(node -> LOOPS.stream().anyMatch(loop -> loop.isInstance(node)))
                .toList();
        return !loops.isEmpty() && loops.stream().noneMatch(loop -> asserts(loop) || !loop.findAll(ThrowStmt.class).isEmpty());
    }

    private static boolean asserts(Node code) {
        return code.findAll(MethodCallExpr.class).stream().anyMatch(UnitTestMethods::isAssertion);
    }

    /**
     * Whether the loop repeats one step rather than going through cases: it counts with a variable its body uses for
     * no data but a label, as {@code for (int i = 0; i < MAX_ATTEMPTS; i++)} exhausting attempts with
     * {@code "wrong-" + i} does. A loop whose counter picks a case, as {@code cases[i]} does, goes through cases.
     */
    private static boolean isRepetition(ForStmt loop) {
        List<String> counters = loop.getInitialization().stream()
                .flatMap(init -> init.findAll(VariableDeclarator.class).stream())
                .map(VariableDeclarator::getNameAsString)
                .toList();
        return counters.size() == 1 && loop.getBody().findAll(NameExpr.class).stream()
                .filter(name -> name.getNameAsString().equals(counters.get(0)))
                .allMatch(BranchingTestsCheck::labels);
    }

    /** Whether the use only puts the counter in text, as {@code "round " + i} does. */
    private static boolean labels(NameExpr use) {
        return use.getParentNode()
                .filter(parent -> parent instanceof BinaryExpr concatenation
                        && concatenation.getOperator() == BinaryExpr.Operator.PLUS
                        && Stream.of(concatenation.getLeft(), concatenation.getRight()).anyMatch(Expression::isStringLiteralExpr))
                .isPresent();
    }

    /** Whether the node is in an anonymous or local class the test declares, or in a lambda that asserts nothing. */
    private static boolean inClassOf(MethodDeclaration test, Node node) {
        return Scopes.nearest(node, MethodDeclaration.class).filter(method -> !method.equals(test)).isPresent()
                || Scopes.nearest(node, LambdaExpr.class).filter(lambda -> lambda.getBody().findAll(MethodCallExpr.class)
                        .stream().noneMatch(UnitTestMethods::isAssertion)).isPresent()
                || Scopes.nearest(node, ObjectCreationExpr.class).filter(creation -> creation.getAnonymousClassBody().isPresent()
                        && creation.getAnonymousClassBody().get().stream().anyMatch(member -> member.isAncestorOf(node)))
                        .isPresent();
    }
}
