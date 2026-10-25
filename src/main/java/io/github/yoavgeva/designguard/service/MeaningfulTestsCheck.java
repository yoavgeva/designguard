package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds tests that prove nothing: tests without any assertion, assertions on constants ({@code assertTrue(true)}),
 * assertions comparing a value with itself or that a new object isn't null, and tests that assert only in a
 * {@code catch} block. Assertions are calls named {@code assert*}, {@code verify*}, {@code expect*}, {@code fail*},
 * AssertJ's {@code then}, MockMvc's {@code andExpect}, ArchUnit's and designguard's {@code check}, or Awaitility's
 * {@code until}, a thrown {@code AssertionError}, and helpers of the test sources that assert; JUnit 4's
 * {@code @Test(expected = ...)} counts too, and so does a name or comment saying the test checks that nothing is thrown,
 * or an empty test in a {@code @SpringBootTest} class, which checks that the application starts.
 * Constants given to a helper of the tests are its inputs, not an assertion on constants.
 */
public final class MeaningfulTestsCheck {

    private static final String RULE = "meaningful-tests";
    private static final String NEVER_FAILS = ", which can never fail";
    /** The classes of JUnit's, AssertJ's, Hamcrest's, Truth's and JSONAssert's assertions, which a call may name. */
    private static final Set<String> ASSERTION_CLASSES = Set.of("Assertions", "Assert", "Assume", "Assumptions",
            "AssertionsForClassTypes", "AssertionsForInterfaceTypes", "BDDAssertions", "SoftAssertions", "MatcherAssert",
            "Truth", "Truth8", "JSONAssert", "TestCase");
    private static final String ASSERT_RESULT = "assert on the result of the code under test";
    /** Tests of equals, hashCode or compareTo compare a value with itself on purpose: reflexivity can fail. */
    private static final Pattern EQUALITY_TEST = Pattern.compile("(?i)equal|hash|compar");
    /**
     * A test's name or comment saying it checks that nothing is thrown, as {@code shouldNotThrowForAnEmptyCart},
     * "nothing thrown", "doesn't actually throw", "should not blow up" and "concurrent modification won't happen" do: a
     * negation, at most one word, then a failure or its happening.
     */
    private static final Pattern NOTHING_THROWN = Pattern.compile("(?i)(?:\\b(?:not|never|no|nothing|without)|n't)"
            + "(?:\\s+\\w+)?\\s+(?:throw|exception|error|fail|blow|crash|explode|happen|occur|raise)");
    /** Where a name in camel case or snake case starts a word. */
    private static final Pattern WORD_START = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|_");

    /**
     * Finds weak tests in the sources the filter selects.
     *
     * @param sources the parsed test sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        AssertingHelpers helpers = new AssertingHelpers(sources);
        return sources.files().stream()
                .flatMap(file -> file.unit().findAll(MethodDeclaration.class).stream()
                        .filter(UnitTestMethods::isTest)
                        .filter(method -> Scopes.selected(filter, file, method))
                        .flatMap(method -> violations(file, method, helpers)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Stream<Violation> violations(ParsedFile file, MethodDeclaration test, AssertingHelpers helpers) {
        List<MethodCallExpr> calls = test.findAll(MethodCallExpr.class);
        String name = "test " + Scopes.describe(test);
        boolean expectsException = UnitTestMethods.annotation(test, "Test")
                .flatMap(annotation -> AnnotationValues.attribute(annotation, "expected"))
                .isPresent();
        boolean checksNothingIsThrown = NOTHING_THROWN.matcher(WORD_START.matcher(test.getNameAsString()).replaceAll(" "))
                .find()
                || test.getAllContainedComments().stream().anyMatch(comment -> NOTHING_THROWN.matcher(comment.getContent()).find());
        if (expectsException || checksNothingIsThrown || checksTheApplicationStarts(test)) {
            return Stream.empty();
        }
        if (!helpers.asserts(test)) {
            return Stream.of(Scopes.violation(RULE, file, test.getName(), name + " asserts nothing", ASSERT_RESULT));
        }
        List<MethodCallExpr> libraryAssertions = calls.stream().filter(call -> inLibraryAssertion(call, helpers)).toList();
        Stream<Violation> constants = libraryAssertions.stream().flatMap(call -> onConstants(file, name, call).stream());
        Stream<Violation> newObjects = libraryAssertions.stream().flatMap(call -> onNewObject(file, name, call).stream());
        Stream<Violation> selfComparisons = EQUALITY_TEST.matcher(test.getNameAsString()).find() ? Stream.empty()
                : libraryAssertions.stream().flatMap(call -> SelfComparisons.comparedWithItself(test, call)
                        .map(value -> Scopes.violation(RULE, file, call, name + " compares " + value + " with itself"
                                + NEVER_FAILS, "compare the result with an expected value"))
                        .stream());
        return Stream.of(onlyInCatch(file, name, test, helpers).stream(), constants, newObjects, selfComparisons)
                .flatMap(violations -> violations);
    }

    /**
     * Whether the test is an empty one in a {@code @SpringBootTest} class, as Spring's {@code contextLoads()} is: booting
     * the application is what it checks.
     */
    private static boolean checksTheApplicationStarts(MethodDeclaration test) {
        return test.getBody().filter(body -> body.getStatements().isEmpty()).isPresent()
                && Scopes.enclosingType(test).filter(type -> Scopes.hasAnnotation(type, "SpringBootTest")).isPresent();
    }

    /** Whether the call is a library assertion, or a check chained onto one, as {@code isEqualTo} is in AssertJ. */
    private static boolean inLibraryAssertion(MethodCallExpr call, AssertingHelpers helpers) {
        return helpers.isLibraryAssertion(call)
                || call.getScope().orElse(null) instanceof MethodCallExpr subject && inLibraryAssertion(subject, helpers);
    }

    /**
     * {@code assertTrue(true)} or {@code assertThat(1).isEqualTo(1)}: the assertion and every check chained onto it
     * take only constants. A constant subject checked against a value, as {@code assertThat("2.8.5").matches(VERSION)}
     * is, can fail.
     */
    private static Optional<Violation> onConstants(ParsedFile file, String name, MethodCallExpr call) {
        if (call.getNameAsString().startsWith("assert") && !call.getArguments().isEmpty() && !isOnAnotherClass(call)
                && chainFrom(call).allMatch(link -> link.getArguments().stream().allMatch(LiteralExpr.class::isInstance))) {
            return Optional.of(Scopes.violation(RULE, file, call, name + " asserts on constants" + NEVER_FAILS, ASSERT_RESULT));
        }
        return Optional.empty();
    }

    /**
     * Whether the call names a class that isn't an assertion library's, as {@code Validate.assertFail(...)} does: the
     * code under test, which may be named like an assertion.
     */
    private static boolean isOnAnotherClass(MethodCallExpr call) {
        return call.getScope()
                .filter(scope -> scope instanceof NodeWithSimpleName<?> named
                        && Character.isUpperCase(named.getNameAsString().charAt(0))
                        && !ASSERTION_CLASSES.contains(named.getNameAsString()))
                .isPresent();
    }

    /** The call and the calls chained onto it, as {@code isEqualTo} is onto {@code assertThat(total)}. */
    private static Stream<MethodCallExpr> chainFrom(MethodCallExpr call) {
        return Stream.iterate(Optional.of(call), Optional::isPresent, link -> link.get().getParentNode()
                        .filter(parent -> parent instanceof MethodCallExpr outer && outer.getScope().filter(link.get()::equals).isPresent())
                        .map(MethodCallExpr.class::cast))
                .map(Optional::get);
    }

    /** {@code assertNotNull(new Cart())} or {@code assertThat(new Cart()).isNotNull()}: {@code new} is never null. */
    private static Optional<Violation> onNewObject(ParsedFile file, String name, MethodCallExpr call) {
        boolean junit = "assertNotNull".equals(call.getNameAsString())
                && call.getArguments().stream().anyMatch(ObjectCreationExpr.class::isInstance);
        boolean assertJ = "isNotNull".equals(call.getNameAsString())
                && call.getScope().orElse(null) instanceof MethodCallExpr subject && "assertThat".equals(subject.getNameAsString())
                && subject.getArguments().size() == 1 && subject.getArgument(0) instanceof ObjectCreationExpr;
        return junit || assertJ
                ? Optional.of(Scopes.violation(RULE, file, call, name + " asserts that a new object isn't null" + NEVER_FAILS,
                        ASSERT_RESULT))
                : Optional.empty();
    }

    /**
     * A test whose every assertion is in a {@code catch} block passes when the code throws nothing. A {@code catch}
     * that only fails checks that nothing is thrown.
     */
    private static Optional<Violation> onlyInCatch(ParsedFile file, String name, MethodDeclaration test,
            AssertingHelpers helpers) {
        List<MethodCallExpr> assertions = test.findAll(MethodCallExpr.class).stream().filter(helpers::isAssertion).toList();
        boolean onlyInCatch = test.findAll(ThrowStmt.class).isEmpty()
                && test.findAll(AssertStmt.class).stream().allMatch(stmt -> Scopes.nearest(stmt, CatchClause.class).isPresent())
                && assertions.stream().anyMatch(call -> !call.getNameAsString().startsWith("fail"))
                && assertions.stream().allMatch(call -> Scopes.nearest(call, CatchClause.class).isPresent());
        return onlyInCatch
                ? Optional.of(Scopes.violation(RULE, file, test.getName(), name
                        + " asserts only in a catch block, so it passes when nothing is thrown",
                        "use assertThrows, or call fail() after the code that should throw"))
                : Optional.empty();
    }
}
