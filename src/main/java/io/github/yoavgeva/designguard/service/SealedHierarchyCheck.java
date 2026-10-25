package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Finds interfaces and abstract classes implemented only inside the project that aren't sealed. Sealing them lets
 * the compiler check switches over them for missing cases. Functional interfaces are skipped, because lambdas
 * implement them and lambdas can't implement a sealed interface. So are service-like ones, which are there to be
 * replaced, by test doubles and a container's proxies, and nothing switches over, and those with an implementation
 * the build generates, as MapStruct does for a mapper, which can't be declared to fit the permits.
 */
public final class SealedHierarchyCheck {

    private static final String RULE = "sealed-hierarchies";

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        JavaSources sources = code.sources();
        ServiceLikeClasses serviceLike = code.serviceLike();
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && isOpenAbstraction(type) && !type.getSubclasses().isEmpty()
                        && !serviceLike.contains(type))
                .filter(type -> sources.declaration(canonicalName(type)).map(declared -> !declared.isSealed()).orElse(false)
                        && type.getSubclasses().stream().allMatch(subclass -> sources.declaration(canonicalName(subclass)).isPresent()))
                .map(type -> {
                    String name = ProjectClasses.nameOf(type);
                    String permits = type.getSubclasses().stream().map(ProjectClasses::nameOf).sorted()
                            .collect(Collectors.joining(", "));
                    String keyword = type.isInterface() ? "sealed interface " : "sealed abstract class ";
                    int line = sources.declaration(canonicalName(type))
                            .map(declared -> Scopes.line(declared.declaration().getName())).orElse(0);
                    return classes.violation(RULE, type, line,
                            name + " is only implemented in this project, but isn't sealed",
                            "declare it sealed: " + keyword + name + " permits " + permits);
                })
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean isOpenAbstraction(JavaClass type) {
        boolean abstraction = type.isInterface() && !type.isAnnotation()
                || !type.isInterface() && type.getModifiers().contains(JavaModifier.ABSTRACT);
        return abstraction && !isFunctionalInterface(type);
    }

    private static boolean isFunctionalInterface(JavaClass type) {
        long abstractMethods = type.getMethods().stream()
                .filter(method -> method.getModifiers().contains(JavaModifier.ABSTRACT))
                .count();
        return type.isInterface() && (abstractMethods == 1
                || ProjectClasses.annotationNames(type).contains("FunctionalInterface"));
    }

    private static String canonicalName(JavaClass type) {
        return type.getName().replace('$', '.');
    }
}
