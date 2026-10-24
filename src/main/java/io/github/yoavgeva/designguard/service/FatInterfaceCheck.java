package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds interfaces wider than any of their clients needs (the interface segregation principle): an interface with at
 * least four abstract methods, used by at least two classes other than its implementations, where each of them calls
 * at most half of its methods. Each client then depends on methods it never uses, and every implementation has to
 * provide them all. Value types the project implements without effects, as a collection or a sink, are left out.
 */
public final class FatInterfaceCheck {

    private static final String RULE = "focused-interfaces";
    private static final int MIN_METHODS = 4;
    private static final int MIN_CLIENTS = 2;

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        ServiceLikeClasses serviceLike = code.serviceLike();
        return classes.stream()
                .filter(type -> type.isInterface() && !type.isAnnotation() && classes.selected(filter, type)
                        && !isValue(type, serviceLike))
                .flatMap(type -> violation(classes, type).stream())
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Optional<Violation> violation(ProjectClasses classes, JavaClass type) {
        Set<String> methods = type.getMethods().stream()
                .filter(method -> method.getModifiers().contains(JavaModifier.ABSTRACT))
                .map(JavaMethod::getFullName)
                .collect(Collectors.toSet());
        if (methods.size() < MIN_METHODS) {
            return Optional.empty();
        }
        Map<JavaClass, Set<String>> usedByClient = type.getAccessesToSelf().stream()
                .filter(access -> methods.contains(access.getTarget().getFullName()))
                .filter(access -> isClient(classes, type, access.getOriginOwner()))
                .collect(Collectors.groupingBy(JavaAccess::getOriginOwner,
                        Collectors.mapping(access -> access.getTarget().getFullName(), Collectors.toSet())));
        int mostUsed = usedByClient.values().stream().mapToInt(Set::size).max().orElse(0);
        if (usedByClient.size() < MIN_CLIENTS || mostUsed * 2 > methods.size()) {
            return Optional.empty();
        }
        String name = ProjectClasses.nameOf(type);
        return Optional.of(classes.violation(RULE, type, 0, name + " has " + methods.size() + " methods, but each of its "
                        + usedByClient.size() + " clients uses at most " + mostUsed + " of them",
                "split it into smaller interfaces, one for each role its clients need, and let implementations"
                        + " implement several")
                .identifiedAs(name + " is wider than any of its clients needs"));
    }

    /**
     * Whether the interface is a value type, as a collection, a graph or a sink is: the project implements it, and no
     * implementation reaches effects. Its clients use the operations of one data type, which nobody fakes.
     */
    private static boolean isValue(JavaClass type, ServiceLikeClasses serviceLike) {
        return !serviceLike.contains(type) && type.getAllSubclasses().stream()
                .anyMatch(subclass -> !subclass.isInterface() && !subclass.getModifiers().contains(JavaModifier.ABSTRACT));
    }

    private static boolean isClient(ProjectClasses classes, JavaClass type, JavaClass origin) {
        return classes.isProject(origin) && !origin.isAssignableTo(type.getName()) && !Hierarchy.isSelfOrNested(type, origin);
    }
}
