package io.github.yoavgeva.designguard.service;

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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ways a test exercises the class it's named after without naming it in a type or a call: through members it imports
 * statically, through a field its container injects typed as an interface or superclass of the class, or, for a web
 * controller or filter, through requests an HTTP test client sends.
 */
final class IndirectUses {

    private static final Set<String> WEB_ENDPOINTS = Set.of("Controller", "RestController", "ControllerAdvice",
            "RestControllerAdvice");
    private static final Set<String> WEB_COMPONENTS = Set.of("jakarta.servlet.Filter", "javax.servlet.Filter",
            "jakarta.servlet.http.HttpServlet", "javax.servlet.http.HttpServlet", "org.springframework.web.server.WebFilter");
    private static final Set<String> HTTP_TEST_CLIENTS = Set.of("MockMvc", "MockMvcTester", "WebTestClient",
            "TestRestTemplate", "RestTestClient", "RestAssured", "RestAssuredMockMvc");
    private static final Set<String> INJECTIONS = Set.of("Autowired", "Inject", "Resource");

    private IndirectUses() {
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
     * Whether the subject handles web requests, as a controller or filter does, and the test sends them through an HTTP
     * test client: the requests exercise it without naming it.
     */
    static boolean isTestedThroughRequests(ParsedFile file, TypeDeclaration<?> test, JavaClass subject) {
        boolean servesRequests = ProjectClasses.annotationNames(subject).stream().anyMatch(WEB_ENDPOINTS::contains)
                || WEB_COMPONENTS.stream().anyMatch(subject::isAssignableTo);
        Stream<String> imported = file.unit().getImports().stream()
                .flatMap(declaration -> Arrays.stream(declaration.getNameAsString().split("\\.")));
        Stream<String> typeNames = test.findAll(ClassOrInterfaceType.class).stream()
                .map(ClassOrInterfaceType::getNameAsString);
        return servesRequests && Stream.concat(imported, typeNames).anyMatch(HTTP_TEST_CLIENTS::contains);
    }
}
