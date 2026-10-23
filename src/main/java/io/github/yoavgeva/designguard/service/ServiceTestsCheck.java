package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.TypeDeclaration;
import com.tngtech.archunit.core.domain.JavaClass;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds service-like classes, the ones with behaviour worth testing, that have no test class named
 * {@code <Name>Test}, {@code <Name>Tests} or {@code <Name>IT} anywhere in the test sources. Wiring code is skipped.
 */
public final class ServiceTestsCheck {

    private static final String RULE = "services-have-tests";

    /**
     * Finds untested service-like classes among those the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        ServiceLikeClasses serviceLike = code.serviceLike();
        WiringCode wiring = code.wiring();
        Set<String> testClasses = code.testSources().files().stream()
                .flatMap(file -> file.unit().getTypes().stream())
                .map(TypeDeclaration::getNameAsString)
                .collect(Collectors.toSet());
        return classes.stream()
                .filter(type -> type.isTopLevelClass() && Hierarchy.isConcrete(type) && serviceLike.contains(type))
                .filter(type -> classes.selected(filter, type) && !wiring.contains(type))
                .filter(type -> testNames(type).noneMatch(testClasses::contains))
                .map(type -> {
                    String expected = type.getSimpleName() + "Test";
                    return classes.violation(RULE, type, 0, type.getSimpleName()
                                    + " is service-like, but has no test class (looked for " + expected + ")",
                            "add " + expected + " that exercises its behaviour");
                })
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Stream<String> testNames(JavaClass type) {
        String name = type.getSimpleName();
        return Stream.of(name + "Test", name + "Tests", name + "IT");
    }
}
