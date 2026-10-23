package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaAccess;
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
 * Finds services obtained instead of injected: an effectful class (one whose own code does I/O, reads the clock...)
 * created with {@code new}, or a service-like class fetched through a static singleton accessor. Wiring code is
 * exempt, and so is creating objects that only receive their collaborators: a class whose effects all happen in
 * implementations it is given, behind interfaces or abstract methods, brings no effects of its own.
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
                .flatMap(dependent -> Stream.concat(creations(classes, filter, serviceLike, dependent),
                        singletons(classes, filter, serviceLike, dependent)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Stream<Violation> creations(ProjectClasses classes, ClassFilter filter, ServiceLikeClasses serviceLike,
            JavaClass dependent) {
        return classes.bytecode().creationsIn(dependent)
                .filter(creation -> serviceLike.isEffectfulOnItsOwn(creation.target())
                        && !Hierarchy.isSelfOrNested(dependent, creation.target())
                        && createdInSelected(classes, filter, dependent, creation))
                .collect(Collectors.toMap(creation -> creation.target().getName(), creation -> creation,
                        (first, second) -> first.line() <= second.line() ? first : second, LinkedHashMap::new))
                .values().stream()
                .map(creation -> {
                    String target = ProjectClasses.nameOf(creation.target());
                    return classes.violation(RULE, dependent, creation.line(),
                            ProjectClasses.nameOf(dependent) + " creates service-like " + target + " with new",
                            "take " + target + " as a constructor parameter, and create it only in wiring code");
                });
    }

    /** Whether the filter selects the method or constructor that creates the object, found by the call's line. */
    private static boolean createdInSelected(ProjectClasses classes, ClassFilter filter, JavaClass dependent,
            ClassDependency creation) {
        return dependent.getConstructorCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().equals(creation.target()) && call.getLineNumber() == creation.line())
                .map(JavaAccess::getOrigin)
                .findFirst()
                .map(origin -> classes.selected(filter, origin))
                .orElse(true);
    }

    private static Stream<Violation> singletons(ProjectClasses classes, ClassFilter filter, ServiceLikeClasses serviceLike,
            JavaClass dependent) {
        return dependent.getMethodCallsFromSelf().stream()
                .filter(call -> classes.selected(filter, call.getOrigin()))
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
