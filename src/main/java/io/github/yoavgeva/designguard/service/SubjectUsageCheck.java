package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.tngtech.archunit.core.domain.JavaClass;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.SuiteTypes.TestType;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds test classes that don't exercise the class they're named after: {@code OrderServiceTest} never uses
 * {@code OrderService}, or only mocks it. A use is the class's name in a type, a static call or an annotation, a
 * statically imported member of it, or an expression whose type the symbol solver resolves to it, such as
 * {@code guard.api()}, in the test or in a test-source helper class it uses. A container-injected field typed as an
 * interface or superclass of it uses it too, and so does a test of a web controller or filter that sends requests
 * through an HTTP test client such as {@code MockMvc}. Tests named after no project class are skipped.
 */
public final class SubjectUsageCheck {

    private static final String RULE = "tests-use-their-subject";
    private static final Pattern TEST_NAME = Pattern.compile("(.+?)(Test|Tests|IT)");
    /** How many test superclasses up a test's inherited uses are looked for. */
    private static final int MAX_SUPERCLASSES = 4;

    /**
     * Finds violations in the test sources the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        SourceTypes types = new SourceTypes(code.projectClasses());
        IndirectUses uses = new IndirectUses(types, code.wiring());
        SuiteTypes tests = new SuiteTypes(code.testSources());
        Context context = new Context(types, uses, tests);
        return tests.all().stream()
                .filter(test -> Scopes.selected(filter, test.file(), test.type()) && !isAbstract(test.type()))
                .flatMap(test -> subject(test.file(), test.type(), types).stream()
                        .flatMap(subject -> violation(test, subject, context).stream()))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /** An abstract test class runs only through its subclasses, which are named after what they test. */
    private static boolean isAbstract(TypeDeclaration<?> test) {
        return test instanceof ClassOrInterfaceDeclaration type && (type.isAbstract() || type.isInterface());
    }

    private static Optional<JavaClass> subject(ParsedFile file, TypeDeclaration<?> test, SourceTypes types) {
        Matcher matcher = TEST_NAME.matcher(test.getNameAsString());
        return matcher.matches() ? types.resolve(file.unit(), matcher.group(1)) : Optional.empty();
    }

    private static Optional<Violation> violation(TestType testType, JavaClass subject, Context context) {
        ParsedFile file = testType.file();
        TypeDeclaration<?> test = testType.type();
        if (uses(testType, subject, context, MAX_SUPERCLASSES)) {
            return Optional.empty();
        }
        List<Node> named = namedUses(file, test, subject, context.types()).toList();
        String name = ProjectClasses.nameOf(subject);
        String testName = test.getNameAsString();
        return Optional.of(named.isEmpty()
                ? Scopes.violation(RULE, file, test.getName(), testName + " never uses " + name
                        + ", the class it's named after", "exercise " + name + " in the test, or name the test after what it tests")
                : Scopes.violation(RULE, file, test.getName(), testName + " only mocks " + name
                        + ", the class it's named after", "test a real " + name + ", and mock only its collaborators"));
    }

    /**
     * Whether the test uses the subject other than through mocks: by name, through a helper, indirectly, or through a
     * test superclass whose tests it runs, as an integration test extending the unit test does.
     */
    private static boolean uses(TestType testType, JavaClass subject, Context context, int depth) {
        ParsedFile file = testType.file();
        TypeDeclaration<?> test = testType.type();
        SourceTypes types = context.types();
        List<MockSites.MockSite> mocks = MockSites.in(file.unit()).filter(mock -> !mock.spy()).toList();
        return namedUses(file, test, subject, types).anyMatch(use -> !MockSites.isMocked(use, mocks))
                || context.tests().helpersOf(testType).anyMatch(helper -> usesReally(helper, subject, types))
                || IndirectUses.resolved(test, subject).anyMatch(use -> !MockSites.isMocked(use, mocks))
                || IndirectUses.isInjectedThroughSupertype(test, subject)
                || context.uses().isRunByTheApplication(file, test, subject)
                || context.uses().isCreatedByAUsedClass(file, test, subject)
                || depth > 0 && context.tests().testSuperclassesOf(testType)
                        .anyMatch(parent -> uses(parent, subject, context, depth - 1));
    }

    /** Whether the helper names the subject other than to mock it. */
    private static boolean usesReally(TestType helper, JavaClass subject, SourceTypes types) {
        List<MockSites.MockSite> mocks = MockSites.in(helper.file().unit()).filter(mock -> !mock.spy()).toList();
        return namedUses(helper.file(), helper.type(), subject, types).anyMatch(use -> !MockSites.isMocked(use, mocks));
    }

    /** Types, static-call scopes, annotations and statically imported members that name the subject. */
    private static Stream<Node> namedUses(ParsedFile file, TypeDeclaration<?> test, JavaClass subject, SourceTypes types) {
        Stream<Node> typeNames = test.findAll(ClassOrInterfaceType.class).stream()
                .filter(type -> types.resolve(file.unit(), type.getNameWithScope())
                        .filter(named -> IndirectUses.standsFor(named, subject)).isPresent())
                .map(type -> type);
        Stream<Node> scopes = test.findAll(NameExpr.class).stream()
                .filter(name -> types.resolve(file.unit(), name.getNameAsString())
                        .filter(named -> IndirectUses.isPartOf(named, subject)).isPresent())
                .map(name -> name);
        Stream<Node> annotations = test.findAll(AnnotationExpr.class).stream()
                .filter(annotation -> types.resolve(file.unit(), annotation.getNameAsString()).filter(subject::equals).isPresent())
                .map(annotation -> annotation);
        return Stream.of(typeNames, scopes, annotations, IndirectUses.staticallyImported(file, test, subject, types))
                .flatMap(uses -> uses);
    }

    /** What a check of one test needs: the source types, indirect uses and the test sources' types. */
    private record Context(SourceTypes types, IndirectUses uses, SuiteTypes tests) {
    }
}
