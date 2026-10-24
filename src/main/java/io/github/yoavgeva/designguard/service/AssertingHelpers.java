package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ThrowStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The methods of the test sources that tests call as helpers, found by name: methods of the calling class, of the
 * classes around it and of their superclasses, and static methods of another test class the call names. A helper
 * asserts if its body does, directly or through other helpers.
 */
final class AssertingHelpers {

    /** How many superclasses up a helper is looked for. */
    private static final int MAX_SUPERCLASSES = 8;

    private final Map<String, List<TypeDeclaration<?>>> typesByName;

    /**
     * Indexes the classes of the test sources.
     *
     * @param sources the parsed test sources
     */
    AssertingHelpers(JavaSources sources) {
        this.typesByName = sources.files().stream()
                .flatMap(file -> file.unit().findAll(TypeDeclaration.class).stream())
                .<TypeDeclaration<?>>map(type -> type)
                .collect(Collectors.groupingBy(TypeDeclaration::getNameAsString));
    }

    /** Whether the code asserts: an assertion call, a throw of an {@code AssertionError}, or a helper that asserts. */
    boolean asserts(Node code) {
        return asserts(code, new HashSet<>());
    }

    /** Whether the call asserts: an assertion of a library or of designguard's naming, or a helper that asserts. */
    boolean isAssertion(MethodCallExpr call) {
        return isAssertion(call, new HashSet<>());
    }

    /** Whether the call is an assertion of a library, such as JUnit's {@code assertEquals}, not a helper of the tests. */
    boolean isLibraryAssertion(MethodCallExpr call) {
        return UnitTestMethods.isAssertion(call) && helpersCalled(call).isEmpty();
    }

    private boolean asserts(Node code, Set<MethodDeclaration> visited) {
        return code.findAll(ThrowStmt.class).stream().anyMatch(AssertingHelpers::throwsAssertionError)
                || code.findAll(MethodCallExpr.class).stream().anyMatch(call -> isAssertion(call, visited));
    }

    private boolean isAssertion(MethodCallExpr call, Set<MethodDeclaration> visited) {
        return UnitTestMethods.isAssertion(call) || helpersCalled(call).stream()
                .anyMatch(helper -> visited.add(helper) && helper.getBody().filter(body -> asserts(body, visited)).isPresent());
    }

    /** The test-source methods the call may run, by name; none for calls of library or production code. */
    private List<MethodDeclaration> helpersCalled(MethodCallExpr call) {
        Stream<TypeDeclaration<?>> owners = call.getScope()
                .map(scope -> typesNamedBy(scope, call))
                .orElseGet(() -> enclosingTypes(call));
        return owners.flatMap(type -> withSuperclasses(type, MAX_SUPERCLASSES))
                .flatMap(type -> type.getMethodsByName(call.getNameAsString()).stream())
                .distinct()
                .toList();
    }

    private Stream<TypeDeclaration<?>> typesNamedBy(Expression scope, MethodCallExpr call) {
        if (scope.isThisExpr() || scope.isSuperExpr()) {
            return enclosingTypes(call).limit(1);
        }
        return scope instanceof NameExpr name
                ? typesByName.getOrDefault(name.getNameAsString(), List.of()).stream()
                : Stream.empty();
    }

    private static Stream<TypeDeclaration<?>> enclosingTypes(Node node) {
        return Stream.iterate(Scopes.enclosingType(node), Optional::isPresent, type -> Scopes.enclosingType(type.get()))
                .map(Optional::get);
    }

    private Stream<TypeDeclaration<?>> withSuperclasses(TypeDeclaration<?> type, int depth) {
        Stream<TypeDeclaration<?>> superclasses = type instanceof ClassOrInterfaceDeclaration declaration && depth > 0
                ? declaration.getExtendedTypes().stream()
                        .flatMap(extended -> typesByName.getOrDefault(extended.getNameAsString(), List.of()).stream())
                        .flatMap(superclass -> withSuperclasses(superclass, depth - 1))
                : Stream.empty();
        return Stream.concat(Stream.of(type), superclasses);
    }

    private static boolean throwsAssertionError(ThrowStmt stmt) {
        return stmt.getExpression() instanceof ObjectCreationExpr creation
                && creation.getType().getNameAsString().matches(".*Assertion(Failed)?Error");
    }
}
