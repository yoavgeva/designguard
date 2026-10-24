package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
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
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds tests with logic ("conditional test logic"): an {@code if} or a {@code switch} in a test method, which makes it
 * check different things on different runs, or a loop around an assertion, which hides which case failed. A loop
 * that only sets up data is fine, and so is one that repeats a step, and logic in helper methods, in classes a test declares, such as a fake, and
 * in lambdas that assert nothing, which are fakes too; the tests themselves take one path.
 */
public final class BranchingTestsCheck {

    private static final String RULE = "tests-have-no-logic";
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
                .filter(found -> !inClassOf(test, found.getKey()))
                .filter(found -> LOOPS.stream().noneMatch(loop -> loop.isInstance(found.getKey()))
                        || found.getKey().findAll(MethodCallExpr.class).stream().anyMatch(UnitTestMethods::isAssertion)
                                && !(found.getKey() instanceof ForStmt loop && isRepetition(loop)))
                .min(Comparator.comparingInt(found -> Scopes.line(found.getKey())));
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
