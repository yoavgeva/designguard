package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds static methods whose own code touches the outside world, when other classes call them: a caller can't be
 * given a fake instead, so it can't be tested without the effect. Private helpers, wiring code and calls from wiring
 * code are exempt, and so are calls from adapters, classes implementing a project interface or abstract class, since
 * their callers can replace them. Effects of implementations a method is given, or of lambdas it returns, aren't its
 * own.
 */
public final class EffectfulStaticsCheck {

    private static final String RULE = "no-effectful-static-methods";

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        ServiceLikeClasses serviceLike = code.serviceLike();
        WiringCode wiring = code.wiring();
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && !wiring.contains(type))
                .flatMap(type -> type.getMethods().stream()
                        .filter(method -> isSharedStatic(method) && classes.selected(filter, method) && serviceLike.hasOwnEffects(method))
                        .flatMap(method -> firstCaller(classes, wiring, type, method).stream()
                                .map(caller -> violation(classes, type, method, caller))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean isSharedStatic(JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        return modifiers.contains(JavaModifier.STATIC) && !modifiers.contains(JavaModifier.PRIVATE)
                && !modifiers.contains(JavaModifier.SYNTHETIC);
    }

    /** The first class by name that calls or references the method and would need to replace it in tests. */
    private static Optional<JavaClass> firstCaller(ProjectClasses classes, WiringCode wiring, JavaClass type, JavaMethod method) {
        return method.getAccessesToSelf().stream()
                .map(JavaAccess::getOriginOwner)
                .filter(caller -> classes.isProject(caller) && !Hierarchy.isSelfOrNested(type, caller)
                        && !wiring.contains(caller) && !isAdapter(classes, caller))
                .min(Comparator.comparing(JavaClass::getName));
    }

    /** Whether the class implements a project interface or abstract class, so its callers can replace it. */
    private static boolean isAdapter(ProjectClasses classes, JavaClass caller) {
        return Stream.concat(caller.getAllRawSuperclasses().stream(), caller.getAllRawInterfaces().stream())
                .anyMatch(parent -> classes.isProject(parent) && !Hierarchy.isConcrete(parent));
    }

    private static Violation violation(ProjectClasses classes, JavaClass type, JavaMethod method, JavaClass caller) {
        return classes.violation(RULE, type, method.getSourceCodeLocation().getLineNumber(),
                ProjectClasses.nameOf(type) + "." + method.getName() + "() is static and touches the outside world, so"
                        + " callers like " + ProjectClasses.nameOf(caller) + " can't replace it in tests",
                "make it an instance method of a class that callers take as a constructor parameter");
    }
}
