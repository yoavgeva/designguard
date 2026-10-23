package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.tngtech.archunit.core.domain.JavaClass;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds test classes that don't exercise the class they're named after: {@code OrderServiceTest} never uses
 * {@code OrderService}, or only mocks it. A use is the class's name in a type, a static call, or an expression whose
 * type the symbol solver resolves to it, such as {@code guard.api()}, in the test or in a test-source helper class it
 * uses. Tests named after no project class are skipped.
 */
public final class SubjectUsageCheck {

    private static final String RULE = "tests-use-their-subject";
    private static final Pattern TEST_NAME = Pattern.compile("(.+?)(Test|Tests|IT)");

    /**
     * Finds violations in the test sources the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        SourceTypes types = new SourceTypes(code.projectClasses());
        Map<String, TestType> testTypes = code.testSources().files().stream()
                .flatMap(file -> file.unit().getTypes().stream().map(type -> new TestType(file, type)))
                .collect(Collectors.toMap(test -> test.type().getNameAsString(), test -> test, (first, second) -> first));
        return testTypes.values().stream()
                .filter(test -> Scopes.selected(filter, test.file(), test.type()))
                .flatMap(test -> subject(test.file(), test.type(), types).stream()
                        .flatMap(subject -> violation(test, subject, types, testTypes).stream()))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Optional<JavaClass> subject(ParsedFile file, TypeDeclaration<?> test, SourceTypes types) {
        Matcher matcher = TEST_NAME.matcher(test.getNameAsString());
        return matcher.matches() ? types.resolve(file.unit(), matcher.group(1)) : Optional.empty();
    }

    private static Optional<Violation> violation(TestType testType, JavaClass subject, SourceTypes types,
            Map<String, TestType> testTypes) {
        ParsedFile file = testType.file();
        TypeDeclaration<?> test = testType.type();
        List<MockSites.MockSite> mocks = MockSites.in(file.unit()).toList();
        List<Node> named = namedUses(file, test, subject, types).toList();
        if (named.stream().anyMatch(use -> !isMock(use, mocks))
                || helpers(testType, testTypes).anyMatch(helper -> usesReally(helper, subject, types))
                || resolvedUses(test, subject).anyMatch(use -> !isMock(use, mocks))) {
            return Optional.empty();
        }
        String name = ProjectClasses.nameOf(subject);
        String testName = test.getNameAsString();
        return Optional.of(named.isEmpty()
                ? Scopes.violation(RULE, file, test.getName(), testName + " never uses " + name
                        + ", the class it's named after", "exercise " + name + " in the test, or name the test after what it tests")
                : Scopes.violation(RULE, file, test.getName(), testName + " only mocks " + name
                        + ", the class it's named after", "test a real " + name + ", and mock only its collaborators"));
    }

    /** The other test-source classes the test names, such as a shared fixture. */
    private static Stream<TestType> helpers(TestType test, Map<String, TestType> testTypes) {
        Stream<String> typeNames = test.type().findAll(ClassOrInterfaceType.class).stream().map(ClassOrInterfaceType::getNameAsString);
        Stream<String> scopes = test.type().findAll(NameExpr.class).stream().map(NameExpr::getNameAsString);
        return Stream.concat(typeNames, scopes)
                .distinct()
                .filter(name -> !name.equals(test.type().getNameAsString()))
                .flatMap(name -> Optional.ofNullable(testTypes.get(name)).stream());
    }

    /** Whether the helper names the subject other than to mock it. */
    private static boolean usesReally(TestType helper, JavaClass subject, SourceTypes types) {
        List<MockSites.MockSite> mocks = MockSites.in(helper.file().unit()).toList();
        return namedUses(helper.file(), helper.type(), subject, types).anyMatch(use -> !isMock(use, mocks));
    }

    /** Whether the use is part of creating a mock, or the type of a variable a mock is assigned to. */
    private static boolean isMock(Node use, List<MockSites.MockSite> mocks) {
        boolean declaresMock = use.getParentNode()
                .filter(VariableDeclarator.class::isInstance)
                .map(VariableDeclarator.class::cast)
                .flatMap(VariableDeclarator::getInitializer)
                .filter(initializer -> mocks.stream().anyMatch(mock -> mock.contains(initializer)))
                .isPresent();
        return declaresMock || mocks.stream().anyMatch(mock -> mock.contains(use));
    }

    /** Types and static-call scopes that name the subject. */
    private static Stream<Node> namedUses(ParsedFile file, TypeDeclaration<?> test, JavaClass subject, SourceTypes types) {
        Stream<Node> typeNames = test.findAll(ClassOrInterfaceType.class).stream()
                .filter(type -> types.resolve(file.unit(), type.getNameWithScope()).filter(subject::equals).isPresent())
                .map(type -> type);
        Stream<Node> scopes = test.findAll(NameExpr.class).stream()
                .filter(name -> types.resolve(file.unit(), name.getNameAsString()).filter(subject::equals).isPresent())
                .map(name -> name);
        return Stream.concat(typeNames, scopes);
    }

    /** Calls whose result the symbol solver types as the subject, as in {@code guard.api()}. */
    private static Stream<Node> resolvedUses(TypeDeclaration<?> test, JavaClass subject) {
        String sourceName = SourceTypes.sourceName(subject);
        return test.findAll(MethodCallExpr.class).stream()
                .filter(call -> Resolution.typeOf(call).filter(sourceName::equals).isPresent())
                .map(call -> call);
    }

    /** A top-level type in the test sources, and its file. */
    private record TestType(ParsedFile file, TypeDeclaration<?> type) {
    }
}
