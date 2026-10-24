package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.Type;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Assertions that compare a value with itself and so can never fail: {@code assertEquals(total, total)} on a
 * number or a string, or {@code assertSame(range, range)} on anything. Comparing an object with itself by equality
 * checks its {@code equals}, which can fail, so that isn't one.
 */
final class SelfComparisons {

    private static final Set<String> EQUALITY_CHECKS = Set.of("isEqualTo", "isEqualByComparingTo");
    private static final Set<String> IDENTITY_CHECKS = Set.of("isSameAs", "assertSame");
    private static final Set<String> PLAIN_TYPES = Set.of("String", "Integer", "Long", "Short", "Byte", "Character",
            "Boolean", "Double", "Float");

    private SelfComparisons() {
    }

    /** The value the assertion compares with itself, if it does so in a way that can never fail. */
    static Optional<String> comparedWithItself(MethodDeclaration test, MethodCallExpr call) {
        return junitSelfComparison(call).or(() -> assertJSelfComparison(call))
                .filter(value -> IDENTITY_CHECKS.contains(call.getNameAsString()) || isPlainValue(test, value));
    }

    /** {@code assertEquals(x, x)}. */
    private static Optional<String> junitSelfComparison(MethodCallExpr call) {
        boolean selfComparison = call.getNameAsString().startsWith("assert") && call.getArguments().size() >= 2
                && sameExpression(call.getArgument(0), call.getArgument(1));
        return selfComparison ? Optional.of(call.getArgument(0).toString()) : Optional.empty();
    }

    /** {@code assertThat(x).isEqualTo(x)}. */
    private static Optional<String> assertJSelfComparison(MethodCallExpr call) {
        String check = call.getNameAsString();
        if (!EQUALITY_CHECKS.contains(check) && !IDENTITY_CHECKS.contains(check) || call.getArguments().size() != 1
                || !(call.getScope().orElse(null) instanceof MethodCallExpr subject)) {
            return Optional.empty();
        }
        boolean selfComparison = "assertThat".equals(subject.getNameAsString()) && subject.getArguments().size() == 1
                && sameExpression(subject.getArgument(0), call.getArgument(0));
        return selfComparison ? Optional.of(call.getArgument(0).toString()) : Optional.empty();
    }

    /**
     * Whether both sides are the same plain value, such as a variable. A call or {@code new} is evaluated twice and can
     * differ, as {@code assertEquals(a.hashCode(), a.hashCode())} checks on purpose.
     */
    private static boolean sameExpression(Expression first, Expression second) {
        return !(first instanceof LiteralExpr) && first.findAll(MethodCallExpr.class).isEmpty()
                && first.findAll(ObjectCreationExpr.class).isEmpty() && first.toString().equals(second.toString());
    }

    /** Whether the variable the test names is declared as a primitive, a boxed primitive or a string. */
    private static boolean isPlainValue(MethodDeclaration test, String name) {
        Stream<Type> locals = test.findAll(VariableDeclarator.class).stream()
                .filter(variable -> variable.getNameAsString().equals(name))
                .map(VariableDeclarator::getType);
        Stream<Type> parameters = test.getParameters().stream()
                .filter(parameter -> parameter.getNameAsString().equals(name))
                .map(Parameter::getType);
        Stream<Type> fields = Scopes.enclosingType(test).stream()
                .flatMap(type -> type.getFields().stream())
                .flatMap(field -> field.getVariables().stream())
                .filter(variable -> variable.getNameAsString().equals(name))
                .map(VariableDeclarator::getType);
        return Stream.of(locals, parameters, fields).flatMap(types -> types).findFirst()
                .filter(type -> type.isPrimitiveType() || PLAIN_TYPES.contains(type.asString()))
                .isPresent();
    }
}
