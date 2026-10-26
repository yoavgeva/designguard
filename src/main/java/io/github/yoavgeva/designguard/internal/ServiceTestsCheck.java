package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds service-like classes, the ones with behaviour worth testing, that have no test class named
 * {@code <Name>Test}, {@code <Name>Tests} or {@code <Name>IT} anywhere in the test sources, nor one named after an
 * interface or superclass of theirs that uses them by name. Wiring code is skipped, and so are classes that only carry
 * data, which have no behaviour of their own.
 */
public final class ServiceTestsCheck {

    private static final String RULE = "services-have-tests";
    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString");

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
        Map<String, TypeDeclaration<?>> testClasses = code.testSources().files().stream()
                .flatMap(file -> file.unit().getTypes().stream())
                .collect(Collectors.toMap(TypeDeclaration::getNameAsString, test -> test, (first, second) -> first));
        return classes.stream()
                .filter(type -> type.isTopLevelClass() && Hierarchy.isConcrete(type) && serviceLike.contains(type)
                        && !carriesOnlyData(type))
                .filter(type -> classes.selected(filter, type) && !wiring.contains(type))
                .filter(type -> testNames(type).noneMatch(testClasses::containsKey) && !isTestedThroughSupertype(type, testClasses))
                .map(type -> {
                    String expected = type.getSimpleName() + "Test";
                    return classes.violation(RULE, type, 0, type.getSimpleName()
                                    + " is service-like, but has no test class (looked for " + expected + ")",
                            "add " + expected + " that exercises its behaviour");
                })
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /**
     * Whether the class only carries data, as an event holding the service it is about does: its constructors only
     * assign fields and call another of its own or the superclass's, and each method it declares returns one of its fields or is
     * {@code equals}, {@code hashCode} or {@code toString}. It has no behaviour of its own to test.
     */
    private static boolean carriesOnlyData(JavaClass type) {
        return type.getConstructors().stream().allMatch(constructor -> constructor.getMethodCallsFromSelf().isEmpty()
                        && constructor.getConstructorCallsFromSelf().stream().allMatch(call -> call.getTargetOwner().equals(type)
                                || type.getRawSuperclass().filter(call.getTargetOwner()::equals).isPresent()))
                && type.getMethods().stream()
                        .filter(method -> !method.getModifiers().contains(JavaModifier.SYNTHETIC))
                        .allMatch(method -> OBJECT_METHODS.contains(method.getName()) || isGetter(type, method));
    }

    /** Whether the method takes nothing, calls nothing and reads one field of the class, as a getter does. */
    private static boolean isGetter(JavaClass type, JavaMethod method) {
        return method.getRawParameterTypes().isEmpty() && method.getCallsFromSelf().isEmpty()
                && method.getFieldAccesses().size() == 1 && method.getFieldAccesses().stream()
                        .allMatch(access -> access.getAccessType() == JavaFieldAccess.AccessType.GET
                                && access.getTargetOwner().equals(type));
    }

    private static Stream<String> testNames(JavaClass type) {
        String name = type.getSimpleName();
        return Stream.of(name + "Test", name + "Tests", name + "IT");
    }

    /**
     * Whether a test named after an interface or superclass of the class, as {@code ViewNameResolverTest} is for
     * {@code DefaultViewNameResolver}, uses the class by name.
     */
    private static boolean isTestedThroughSupertype(JavaClass type, Map<String, TypeDeclaration<?>> testClasses) {
        return Stream.concat(type.getAllRawInterfaces().stream(), type.getAllRawSuperclasses().stream())
                .flatMap(ServiceTestsCheck::testNames)
                .flatMap(name -> Optional.ofNullable(testClasses.get(name)).stream())
                .anyMatch(test -> test.findAll(ClassOrInterfaceType.class).stream()
                        .anyMatch(used -> used.getNameAsString().equals(type.getSimpleName())));
    }
}
