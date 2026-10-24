package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;

/**
 * Finds values with mutable state: classes that aren't service-like (no I/O, clock, randomness...) but have a
 * non-final instance field. A field a container injects is reported as one to take in the constructor. Enums,
 * records, exceptions, wiring code and persistence entities are skipped.
 */
public final class ValueImmutabilityCheck {

    private static final String RULE = "immutable-values";

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
                .filter(value -> classes.selected(filter, value) && isValue(value, serviceLike) && !wiring.contains(value))
                .flatMap(value -> value.getFields().stream()
                        .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC)
                                && !field.getModifiers().contains(JavaModifier.FINAL)
                                && !field.getModifiers().contains(JavaModifier.SYNTHETIC)
                                && classes.selected(filter, field))
                        .map(field -> violation(classes, value, field)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Violation violation(ProjectClasses classes, JavaClass value, JavaField field) {
        String name = ProjectClasses.nameOf(value);
        if (WiringCode.isInjected(field)) {
            return classes.violation(RULE, value, 0, name + " is a value, but its injected field '" + field.getName()
                    + "' isn't final", WiringCode.INJECT_IN_CONSTRUCTOR);
        }
        return classes.violation(RULE, value, 0, name + " is a value, but its field '" + field.getName() + "' isn't final",
                "make the field final and return a new " + name + " instead of changing it, or turn " + name + " into a record");
    }

    private static boolean isValue(JavaClass javaClass, ServiceLikeClasses serviceLike) {
        return ServiceLikeClasses.canBeServiceLike(javaClass)
                && !serviceLike.contains(javaClass)
                && !javaClass.isEnum()
                && !javaClass.isRecord()
                && !javaClass.isInterface()
                && !ServiceLikeClasses.isPersistent(javaClass);
    }
}
