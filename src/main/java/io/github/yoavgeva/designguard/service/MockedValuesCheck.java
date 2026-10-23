package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.tngtech.archunit.core.domain.JavaClass;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;

/**
 * Finds tests that mock values: records, enums and other classes that aren't service-like, and interfaces whose
 * implementations all are values. A value is cheap and safe to create, so a test should use a real one; a mock of it
 * tests the mock. Mocks come from Mockito's or EasyMock's {@code mock(Type.class)}, {@code spy(Type.class)} and
 * {@code createMock(Type.class)}, and from fields and parameters annotated {@code @Mock}, {@code @Spy} or Spring's
 * mock-bean annotations.
 */
public final class MockedValuesCheck {

    private static final String RULE = "tests-dont-mock-values";

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
        SourceTypes types = new SourceTypes(classes);
        return code.testSources().files().stream()
                .flatMap(file -> MockSites.in(file.unit())
                        .filter(mock -> Scopes.selected(filter, file, mock.site()))
                        .flatMap(mock -> types.resolve(file.unit(), mock.typeName()).stream()
                                .filter(type -> isValue(classes, serviceLike, type))
                                .map(type -> violation(file, mock.site(), type))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
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
}
