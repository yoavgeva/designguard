package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds services obtained instead of injected: an effectful class (one whose code does I/O, reads the clock...)
 * created with {@code new}, or a service-like class fetched through a static singleton accessor. Wiring code is
 * exempt, and so is creating objects that only receive their collaborators.
 */
public final class InjectServicesCheck {

    private static final String RULE = "inject-services";

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
                .filter(dependent -> classes.selected(filter, dependent) && !wiring.contains(dependent))
                .flatMap(dependent -> Stream.concat(
                        creations(classes, serviceLike, dependent), singletons(classes, serviceLike, dependent)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Stream<Violation> creations(ProjectClasses classes, ServiceLikeClasses serviceLike, JavaClass dependent) {
        return dependent.getConstructorCallsFromSelf().stream()
                .filter(call -> classes.isProject(call.getTargetOwner()) && serviceLike.isEffectful(call.getTargetOwner())
                        && !Hierarchy.isSelfOrNested(dependent, call.getTargetOwner()))
                .collect(Collectors.toMap(call -> call.getTargetOwner().getName(), call -> call,
                        (first, second) -> first.getLineNumber() <= second.getLineNumber() ? first : second,
                        LinkedHashMap::new))
                .values().stream()
                .map(call -> {
                    String target = ProjectClasses.nameOf(call.getTargetOwner());
                    return classes.violation(RULE, dependent, call.getLineNumber(),
                            ProjectClasses.nameOf(dependent) + " creates service-like " + target + " with new",
                            "take " + target + " as a constructor parameter, and create it only in wiring code");
                });
    }

    private static Stream<Violation> singletons(ProjectClasses classes, ServiceLikeClasses serviceLike, JavaClass dependent) {
        return dependent.getMethodCallsFromSelf().stream()
                .filter(call -> isForeignService(classes, serviceLike, dependent, call.getTargetOwner()))
                .filter(InjectServicesCheck::returnsItsOwnerStatically)
                .collect(Collectors.toMap(JavaMethodCall::getName, call -> call, (first, second) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .map(call -> {
                    String target = ProjectClasses.nameOf(call.getTargetOwner());
                    return classes.violation(RULE, dependent, call.getLineNumber(),
                            ProjectClasses.nameOf(dependent) + " reaches service-like " + target + " through the static "
                                    + target + "." + call.getName() + "()",
                            "take " + target + " as a constructor parameter instead");
                });
    }

    private static boolean isForeignService(
            ProjectClasses classes, ServiceLikeClasses serviceLike, JavaClass dependent, JavaClass target) {
        return classes.isProject(target) && serviceLike.contains(target) && !Hierarchy.isSelfOrNested(dependent, target);
    }

    /** A singleton accessor: a static, parameterless method returning its class, which keeps itself in a field. */
    private static boolean returnsItsOwnerStatically(JavaMethodCall call) {
        JavaClass owner = call.getTargetOwner();
        boolean keepsInstance = owner.getFields().stream()
                .anyMatch(field -> field.getModifiers().contains(JavaModifier.STATIC) && field.getRawType().equals(owner));
        return keepsInstance && call.getTarget().resolveMember()
                .filter(method -> method.getModifiers().contains(JavaModifier.STATIC))
                .filter(method -> method.getRawParameterTypes().isEmpty())
                .map(method -> method.getRawReturnType().equals(owner))
                .orElse(false);
    }
}
