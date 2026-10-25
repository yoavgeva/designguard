package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where tests create mocks: Mockito's or EasyMock's {@code mock(Type.class)}, {@code spy(Type.class)},
 * {@code spy(new Type(...))} and {@code createMock(Type.class)}, and fields and parameters annotated {@code @Mock},
 * {@code @Spy} or Spring's mock-bean annotations.
 */
final class MockSites {

    private static final Set<String> MOCK_CALLS = Set.of("mock", "spy", "createMock");
    /** Mockito's and BDDMockito's stubbing calls: on a mock's call, or, after {@code doReturn(...)}, on the mock. */
    private static final Set<String> STUBS = Set.of("when", "given");
    private static final List<String> MOCK_ANNOTATIONS =
            List.of("Mock", "Spy", "MockBean", "SpyBean", "MockitoBean", "MockitoSpyBean");
    private static final List<String> SPY_ANNOTATIONS = List.of("Spy", "SpyBean", "MockitoSpyBean");

    private MockSites() {
    }

    /** Every mock the file creates. */
    static Stream<MockSite> in(CompilationUnit unit) {
        Stream<MockSite> created = unit.findAll(MethodCallExpr.class).stream()
                .filter(call -> MOCK_CALLS.contains(call.getNameAsString()) && !call.getArguments().isEmpty())
                .flatMap(call -> Stream.concat(call.getArgument(0).toClassExpr().map(ClassExpr::getType).stream(),
                                call.getArgument(0).toObjectCreationExpr().map(ObjectCreationExpr::getType).stream())
                        .flatMap(type -> SourceTypes.named(type).stream())
                        .map(name -> new MockSite(call, name, "spy".equals(call.getNameAsString()))));
        Stream<MockSite> fields = unit.findAll(FieldDeclaration.class).stream()
                .filter(MockSites::isMock)
                .flatMap(field -> SourceTypes.named(field.getElementType()).stream()
                        .map(name -> new MockSite(field.getElementType(), name, isSpy(field))));
        Stream<MockSite> parameters = unit.findAll(Parameter.class).stream()
                .filter(MockSites::isMock)
                .flatMap(parameter -> SourceTypes.named(parameter.getType()).stream()
                        .map(name -> new MockSite(parameter.getType(), name, isSpy(parameter))));
        return Stream.of(created, fields, parameters).flatMap(mocks -> mocks);
    }

    /**
     * The calls the test verifies on the mock, as {@code verify(closed).close()} does, if it never stubs it, as
     * {@code when(...)}, {@code given(...)} and {@code doReturn(...).when(...)} do; none if it stubs it or verifies
     * nothing on it.
     */
    static List<MethodCallExpr> verifiedCalls(CompilationUnit unit, MockSite mock) {
        Optional<String> variable = variableOf(mock);
        if (variable.isEmpty()) {
            return List.of();
        }
        String name = variable.get();
        List<MethodCallExpr> calls = unit.findAll(MethodCallExpr.class);
        boolean stubbed = calls.stream().anyMatch(call -> STUBS.contains(call.getNameAsString()) && call.getArguments().size() == 1
                && (isName(call.getArgument(0), name) || call.getArgument(0) instanceof MethodCallExpr stubbedCall
                        && stubbedCall.getScope().filter(scope -> isName(scope, name)).isPresent()));
        return stubbed ? List.of() : calls.stream()
                .filter(call -> call.getScope().filter(scope -> scope instanceof MethodCallExpr verify
                        && "verify".equals(verify.getNameAsString()) && !verify.getArguments().isEmpty()
                        && isName(verify.getArgument(0), name)).isPresent())
                .toList();
    }

    /** The name of the variable, field or parameter the mock is held in. */
    private static Optional<String> variableOf(MockSite mock) {
        Node site = mock.site();
        Optional<Node> parent = site.getParentNode();
        if (parent.orElse(null) instanceof VariableDeclarator variable) {
            return Optional.of(variable.getNameAsString());
        }
        if (parent.orElse(null) instanceof FieldDeclaration field && field.getVariables().size() == 1) {
            return Optional.of(field.getVariable(0).getNameAsString());
        }
        if (parent.orElse(null) instanceof Parameter parameter) {
            return Optional.of(parameter.getNameAsString());
        }
        return Optional.empty();
    }

    private static boolean isName(Node node, String name) {
        return node instanceof NameExpr expression && expression.getNameAsString().equals(name);
    }

    /** Whether the use is part of creating a mock, or the type of a variable a mock is assigned to. */
    static boolean isMocked(Node use, List<MockSite> mocks) {
        boolean declaresMock = use.getParentNode()
                .filter(VariableDeclarator.class::isInstance)
                .map(VariableDeclarator.class::cast)
                .flatMap(VariableDeclarator::getInitializer)
                .filter(initializer -> mocks.stream().anyMatch(mock -> mock.contains(initializer)))
                .isPresent();
        return declaresMock || mocks.stream().anyMatch(mock -> mock.contains(use));
    }

    private static boolean isMock(NodeWithAnnotations<?> member) {
        return MOCK_ANNOTATIONS.stream().anyMatch(annotation -> Scopes.hasAnnotation(member, annotation));
    }

    private static boolean isSpy(NodeWithAnnotations<?> member) {
        return SPY_ANNOTATIONS.stream().anyMatch(annotation -> Scopes.hasAnnotation(member, annotation));
    }

    /**
     * Where a test creates a mock, and the mocked type's name as written there.
     *
     * @param site     the creating call, or the annotated field's or parameter's type
     * @param typeName the type's name, e.g. {@code Order.Line}
     * @param spy      whether it is a spy, which runs the type's real code where the test doesn't stub it
     */
    record MockSite(Node site, String typeName, boolean spy) {

        /** Whether the node is the site or inside it. */
        boolean contains(Node node) {
            return site.isAncestorOf(node)
                    || site.getClass().equals(node.getClass()) && site.getRange().equals(node.getRange());
        }
    }
}
