package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMember;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ways a test exercises the class it's named after without naming it in a type or a call: through an implementation of
 * it, members it imports statically, a field its container injects typed as an interface or superclass of the class,
 * a class it uses that creates or calls it, or the running application: a {@code @SpringBootTest} boots the
 * {@code @SpringBootApplication} class, and requests an HTTP test client sends reach a web controller or filter, or in a
 * booted application any Spring bean.
 */
final class IndirectUses {

    private static final Set<String> WEB_ENDPOINTS = Set.of("Controller", "RestController", "ControllerAdvice",
            "RestControllerAdvice");
    private static final Set<String> WEB_COMPONENTS = Set.of("jakarta.servlet.Filter", "javax.servlet.Filter",
            "jakarta.servlet.http.HttpServlet", "javax.servlet.http.HttpServlet", "org.springframework.web.server.WebFilter");
    private static final Set<String> HTTP_TEST_CLIENTS = Set.of("MockMvc", "MockMvcTester", "WebTestClient",
            "TestRestTemplate", "RestTestClient", "RestAssured", "RestAssuredMockMvc", "HttpClient", "BlockingHttpClient");
    private static final Set<String> INJECTIONS = Set.of("Autowired", "Inject", "Resource");
    private static final Set<String> COMPONENTS = Set.of("Component", "Service", "Repository", "Controller",
            "RestController", "ControllerAdvice", "RestControllerAdvice", "Configuration", "ApplicationScoped",
            "RequestScoped", "Singleton", "Dependent");
    private static final Set<String> BOOTED_TESTS = Set.of("SpringBootTest", "WebMvcTest", "WebFluxTest", "QuarkusTest",
            "QuarkusIntegrationTest", "MicronautTest");
    /** JAX-RS resources and providers, such as exception mappers and filters, which serve requests. */
    private static final List<String> JAX_RS = List.of("jakarta.ws.rs.Path", "javax.ws.rs.Path", "jakarta.ws.rs.ext.Provider",
            "javax.ws.rs.ext.Provider");

    private final SourceTypes types;
    private final WiringCode wiring;

    IndirectUses(SourceTypes types, WiringCode wiring) {
        this.types = types;
        this.wiring = wiring;
    }

    /** Calls whose result the symbol solver types as the subject, as in {@code guard.api()}. */
    static Stream<Node> resolved(TypeDeclaration<?> test, JavaClass subject) {
        String sourceName = SourceTypes.sourceName(subject);
        return test.findAll(MethodCallExpr.class).stream()
                .filter(call -> Resolution.typeOf(call).filter(sourceName::equals).isPresent())
                .map(call -> call);
    }

    /**
     * Whether a type the test names stands for the subject: it is the subject or a type nested in it, as
     * {@code HtmlTreeBuilderState.Constants} is, or implements the abstract subject.
     */
    static boolean standsFor(JavaClass named, JavaClass subject) {
        return isPartOf(named, subject) || !Hierarchy.isConcrete(subject) && named.isAssignableTo(subject.getName());
    }

    /** Whether the type is the subject or nested in it. */
    static boolean isPartOf(JavaClass named, JavaClass subject) {
        return named.equals(subject) || named.getName().startsWith(subject.getName() + "$");
    }

    /** Calls and names of the subject's members the file imports statically, as {@code encode(text)} is. */
    static Stream<Node> staticallyImported(ParsedFile file, TypeDeclaration<?> test, JavaClass subject, SourceTypes types) {
        Set<String> imported = importedMembers(file, subject, types);
        return Stream.concat(
                        test.findAll(MethodCallExpr.class).stream().filter(call -> call.getScope().isEmpty()).map(call -> (Node) call),
                        test.findAll(NameExpr.class).stream().map(name -> (Node) name))
                .filter(use -> use instanceof NodeWithSimpleName<?> named && imported.contains(named.getNameAsString()));
    }

    /** Names of the subject's members the file imports statically, one by one or with {@code *}. */
    private static Set<String> importedMembers(ParsedFile file, JavaClass subject, SourceTypes types) {
        return file.unit().getImports().stream()
                .filter(ImportDeclaration::isStatic)
                .flatMap(declaration -> declaration.isAsterisk()
                        ? types.resolve(file.unit(), declaration.getNameAsString()).filter(subject::equals).stream()
                                .flatMap(type -> type.getMembers().stream().map(JavaMember::getName))
                        : declaration.getName().getQualifier()
                                .flatMap(owner -> types.resolve(file.unit(), owner.asString()))
                                .filter(subject::equals)
                                .map(type -> declaration.getName().getIdentifier())
                                .stream())
                .collect(Collectors.toSet());
    }

    /** Whether the test's container injects the subject into a field typed as an interface or superclass of it. */
    static boolean isInjectedThroughSupertype(TypeDeclaration<?> test, JavaClass subject) {
        Set<String> supertypes = Stream.concat(subject.getAllRawInterfaces().stream(), subject.getAllRawSuperclasses().stream())
                .filter(type -> !Object.class.getName().equals(type.getName()))
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());
        return test.findAll(FieldDeclaration.class).stream()
                .filter(field -> field.getAnnotations().stream()
                        .anyMatch(annotation -> INJECTIONS.contains(annotation.getName().getIdentifier())))
                .anyMatch(field -> field.getElementType() instanceof ClassOrInterfaceType type
                        && supertypes.contains(type.getNameAsString()));
    }

    /**
     * Whether the application the test runs exercises the subject: a {@code @SpringBootTest} boots the
     * {@code @SpringBootApplication} class, and requests the test sends through an HTTP test client reach a web
     * controller or filter, or, in an application the test boots, any Spring bean: a component, or a class the wiring
     * code creates, as a {@code @Bean} method does.
     */
    boolean isRunByTheApplication(ParsedFile file, TypeDeclaration<?> test, JavaClass subject) {
        List<String> subjectAnnotations = ProjectClasses.annotationNames(subject);
        boolean boots = test.getAnnotations().stream().anyMatch(annotation -> BOOTED_TESTS.contains(annotation.getName().getIdentifier()));
        if (boots && subjectAnnotations.contains("SpringBootApplication")) {
            return true;
        }
        boolean servesRequests = subjectAnnotations.stream().anyMatch(WEB_ENDPOINTS::contains)
                || JAX_RS.stream().anyMatch(subject::isAnnotatedWith)
                || WEB_COMPONENTS.stream().anyMatch(subject::isAssignableTo)
                || boots && (subjectAnnotations.stream().anyMatch(COMPONENTS::contains) || isCreatedBy(subject, wiring::contains));
        return servesRequests && sendsRequests(file, test);
    }

    /**
     * Whether a project class the test uses, other than through a mock, reaches the subject: creates it, as a dialect
     * creates its processors, calls it from a method the test calls, as a document's {@code select(...)} calls the
     * selector, or registers it by its
     * class, as a Jackson module registers a mixin with {@code setMixInAnnotation(Money.class, MoneyMixin.class)}, or
     * the test calls a method the subject overrides on a supertype it names. A used class's superclasses count, since
     * their code runs, and a mocked class runs none of its own.
     */
    boolean isCreatedByAUsedClass(ParsedFile file, TypeDeclaration<?> test, JavaClass subject) {
        Set<JavaClass> named = UsedClasses.named(file, test, subject, types);
        Set<JavaClass> used = named.stream()
                .flatMap(type -> Stream.concat(Stream.of(type), type.getAllRawSuperclasses().stream()))
                .collect(Collectors.toSet());
        Set<String> called = test.findAll(MethodCallExpr.class).stream().map(MethodCallExpr::getNameAsString)
                .collect(Collectors.toSet());
        return isCreatedBy(subject, used::contains) || runsAnOverride(subject, named, called)
                || subject.getMethodCallsToSelf().stream().anyMatch(call -> used.contains(call.getOriginOwner())
                        && called.contains(call.getOrigin().getName()))
                || used.stream()
                        .flatMap(type -> type.getReferencedClassObjects().stream())
                        .anyMatch(reference -> reference.getRawType().equals(subject));
    }

    /**
     * Whether the test calls, on a supertype of the subject it names, a method the subject overrides, as
     * {@code node.hasAttributes()} runs a leaf node's own: on the subject's instances, its code runs.
     */
    private static boolean runsAnOverride(JavaClass subject, Set<JavaClass> named, Set<String> called) {
        return Stream.concat(subject.getAllRawSuperclasses().stream(), subject.getAllRawInterfaces().stream())
                        .anyMatch(named::contains)
                && subject.getMethods().stream()
                        .anyMatch(method -> called.contains(method.getName()) && Hierarchy.isInherited(subject, method));
    }

    private static boolean isCreatedBy(JavaClass subject, Predicate<JavaClass> creators) {
        return subject.getConstructorCallsToSelf().stream().anyMatch(call -> creators.test(call.getOriginOwner()));
    }

    private static boolean sendsRequests(ParsedFile file, TypeDeclaration<?> test) {
        Stream<String> imported = file.unit().getImports().stream()
                .flatMap(declaration -> Arrays.stream(declaration.getNameAsString().split("\\.")));
        Stream<String> typeNames = test.findAll(ClassOrInterfaceType.class).stream()
                .map(ClassOrInterfaceType::getNameAsString);
        return Stream.concat(imported, typeNames).anyMatch(HTTP_TEST_CLIENTS::contains);
    }
}
