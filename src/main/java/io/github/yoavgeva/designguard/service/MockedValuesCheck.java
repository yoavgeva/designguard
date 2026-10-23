package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.tngtech.archunit.core.domain.JavaClass;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds tests that mock values: records, enums and other classes that aren't service-like, and interfaces whose
 * implementations all are values. A value is cheap and safe to create, so a test should use a real one; a mock of it
 * tests the mock. Mocks come from Mockito's or EasyMock's {@code mock(Type.class)}, {@code spy(Type.class)} and
 * {@code createMock(Type.class)}, and from fields and parameters annotated {@code @Mock}, {@code @Spy} or Spring's
 * mock-bean annotations.
 */
public final class MockedValuesCheck {

    private static final String RULE = "tests-dont-mock-values";
    private static final Set<String> MOCK_CALLS = Set.of("mock", "spy", "createMock");
    private static final List<String> MOCK_ANNOTATIONS =
            List.of("Mock", "Spy", "MockBean", "SpyBean", "MockitoBean", "MockitoSpyBean");

    /**
     * Finds violations in the test sources the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        ServiceLikeClasses serviceLike = code.serviceLike();
        Map<String, JavaClass> bySourceName = classes.stream()
                .collect(Collectors.toMap(type -> type.getName().replace('$', '.'), type -> type, (first, second) -> first));
        return code.testSources().files().stream()
                .flatMap(file -> mocks(file.unit())
                        .filter(mock -> Scopes.selected(filter, file, mock.site()))
                        .flatMap(mock -> resolve(file.unit(), mock.typeName(), bySourceName).stream()
                                .filter(type -> isValue(classes, serviceLike, type))
                                .map(type -> violation(file, mock.site(), type))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Stream<Mock> mocks(CompilationUnit unit) {
        Stream<Mock> created = unit.findAll(MethodCallExpr.class).stream()
                .filter(call -> MOCK_CALLS.contains(call.getNameAsString()) && !call.getArguments().isEmpty())
                .flatMap(call -> call.getArgument(0).toClassExpr().map(ClassExpr::getType).stream()
                        .flatMap(type -> named(type).stream())
                        .map(name -> new Mock(call, name)));
        Stream<Mock> fields = unit.findAll(FieldDeclaration.class).stream()
                .filter(MockedValuesCheck::isMock)
                .flatMap(field -> named(field.getElementType()).stream().map(name -> new Mock(field.getElementType(), name)));
        Stream<Mock> parameters = unit.findAll(Parameter.class).stream()
                .filter(MockedValuesCheck::isMock)
                .flatMap(parameter -> named(parameter.getType()).stream().map(name -> new Mock(parameter.getType(), name)));
        return Stream.of(created, fields, parameters).flatMap(mocks -> mocks);
    }

    private static boolean isMock(NodeWithAnnotations<?> member) {
        return MOCK_ANNOTATIONS.stream().anyMatch(annotation -> Scopes.hasAnnotation(member, annotation));
    }

    /** The type's name as written, with its scope but without type arguments, e.g. {@code Order.Line}. */
    private static Optional<String> named(Type type) {
        return type.toClassOrInterfaceType().map(ClassOrInterfaceType::getNameWithScope);
    }

    /**
     * The project class a name written in the test file refers to: as written, through the file's single-type
     * imports, in the file's package, or through its on-demand imports, in that order.
     */
    private static Optional<JavaClass> resolve(CompilationUnit unit, String written, Map<String, JavaClass> bySourceName) {
        String first = written.contains(".") ? written.substring(0, written.indexOf('.')) : written;
        String rest = written.substring(first.length());
        String packagePrefix = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString() + ".").orElse("");
        List<ImportDeclaration> imports = unit.getImports().stream().filter(declaration -> !declaration.isStatic()).toList();
        Stream<String> candidates = Stream.of(
                Stream.of(written),
                imports.stream().filter(declaration -> !declaration.isAsterisk()
                                && declaration.getName().getIdentifier().equals(first))
                        .map(declaration -> declaration.getNameAsString() + rest),
                Stream.of(packagePrefix + written),
                imports.stream().filter(ImportDeclaration::isAsterisk)
                        .map(declaration -> declaration.getNameAsString() + "." + written))
                .flatMap(names -> names);
        return candidates.map(bySourceName::get).filter(type -> type != null).findFirst();
    }

    /** A class that isn't service-like, or an abstraction whose project implementations all are values. */
    private static boolean isValue(ProjectClasses classes, ServiceLikeClasses serviceLike, JavaClass type) {
        if (serviceLike.contains(type)) {
            return false;
        }
        return Hierarchy.isConcrete(type) || classes.stream()
                .anyMatch(other -> !other.equals(type) && Hierarchy.isConcrete(other) && other.isAssignableTo(type.getName()));
    }

    private static Violation violation(ParsedFile file, Node site, JavaClass type) {
        String test = Scopes.enclosingType(site).map(declaration -> declaration.getNameAsString()).orElse("a test");
        String name = ProjectClasses.nameOf(type);
        return Scopes.violation(RULE, file, site, test + " mocks " + name + ", which is a value",
                "use a real " + name + " instead; mock only service-like types");
    }

    /** Where a test creates a mock, and the type's name as written there. */
    private record Mock(Node site, String typeName) {
    }
}
