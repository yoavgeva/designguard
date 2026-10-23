package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds tests that prove nothing: tests without any assertion, assertions on constants ({@code assertTrue(true)}),
 * and assertions comparing a value with itself. Assertions are calls named {@code assert*}, {@code verify*},
 * {@code expect*}, {@code fail*}, AssertJ's {@code then}, MockMvc's {@code andExpect}, ArchUnit's and
 * designguard's {@code check}, or Awaitility's {@code until}; JUnit 4's {@code @Test(expected = ...)} counts too.
 */
public final class MeaningfulTestsCheck {

    private static final String RULE = "meaningful-tests";
    private static final String NEVER_FAILS = ", which can never fail";
    private static final String ASSERT_RESULT = "assert on the result of the code under test";
    private static final Set<String> EQUALITY_CHECKS = Set.of("isEqualTo", "isSameAs", "isEqualByComparingTo");
    /** Tests of equals, hashCode or compareTo compare a value with itself on purpose: reflexivity can fail. */
    private static final Pattern EQUALITY_TEST = Pattern.compile("(?i)equal|hash|compar");

    /**
     * Finds weak tests in the sources the filter selects.
     *
     * @param sources the parsed test sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .flatMap(file -> file.unit().findAll(MethodDeclaration.class).stream()
                        .filter(UnitTestMethods::isTest)
                        .filter(method -> Scopes.selected(filter, file, method))
                        .flatMap(method -> violations(file, method)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Stream<Violation> violations(ParsedFile file, MethodDeclaration test) {
        List<MethodCallExpr> calls = test.findAll(MethodCallExpr.class);
        String name = "test " + Scopes.describe(test);
        boolean expectsException = UnitTestMethods.annotation(test, "Test")
                .flatMap(annotation -> AnnotationValues.attribute(annotation, "expected"))
                .isPresent();
        if (calls.stream().noneMatch(UnitTestMethods::isAssertion) && !expectsException) {
            return Stream.of(Scopes.violation(RULE, file, test.getName(), name + " asserts nothing", ASSERT_RESULT));
        }
        Stream<Violation> constants = calls.stream().flatMap(call -> onConstants(file, name, call).stream());
        Stream<Violation> selfComparisons = EQUALITY_TEST.matcher(test.getNameAsString()).find() ? Stream.empty()
                : calls.stream().flatMap(call -> comparedWithItself(call).map(value -> Scopes.violation(RULE, file, call,
                        name + " compares " + value + " with itself" + NEVER_FAILS, "compare the result with an expected value"))
                        .stream());
        return Stream.concat(constants, selfComparisons);
    }

    private static Optional<Violation> onConstants(ParsedFile file, String name, MethodCallExpr call) {
        if (call.getNameAsString().startsWith("assert") && !call.getArguments().isEmpty()
                && call.getArguments().stream().allMatch(LiteralExpr.class::isInstance)) {
            return Optional.of(Scopes.violation(RULE, file, call, name + " asserts on constants" + NEVER_FAILS, ASSERT_RESULT));
        }
        return Optional.empty();
    }

    private static Optional<String> comparedWithItself(MethodCallExpr call) {
        return junitSelfComparison(call).or(() -> assertJSelfComparison(call));
    }

    /** {@code assertEquals(x, x)}. */
    private static Optional<String> junitSelfComparison(MethodCallExpr call) {
        boolean selfComparison = call.getNameAsString().startsWith("assert") && call.getArguments().size() >= 2
                && sameExpression(call.getArgument(0), call.getArgument(1));
        return selfComparison ? Optional.of(call.getArgument(0).toString()) : Optional.empty();
    }

    /** {@code assertThat(x).isEqualTo(x)}. */
    private static Optional<String> assertJSelfComparison(MethodCallExpr call) {
        if (!EQUALITY_CHECKS.contains(call.getNameAsString()) || call.getArguments().size() != 1
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

}
