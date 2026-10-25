package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds values with mutable state: classes that aren't service-like (no I/O, clock, randomness...) but have a
 * non-final instance field. A field a container injects is reported as one to take in the constructor. Enums,
 * records, exceptions, wiring code, persistence entities and builders, which change on purpose, are skipped, and so
 * are the other fields of components a container manages ({@link ManagedComponents}), which aren't values.
 */
public final class ValueImmutabilityCheck {

    private static final String RULE = "immutable-values";
    private static final Pattern FINISHER = Pattern.compile("(build|create)[A-Z].*");

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
                                && (!ManagedComponents.contains(value) || WiringCode.isInjected(field))
                                && classes.selected(filter, field))
                        .map(field -> violation(classes, value, field)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Violation violation(ProjectClasses classes, JavaClass value, JavaField field) {
        String name = ProjectClasses.nameOf(value);
        if (WiringCode.isInjected(field)) {
            String kind = ManagedComponents.contains(value) ? " is a component" : " is a value";
            return classes.violation(RULE, value, 0, name + kind + ", but its injected field '" + field.getName()
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
                && !ServiceLikeClasses.isPersistent(javaClass)
                && !isBuilder(javaClass);
    }

    /**
     * A builder: it changes on purpose, and what it builds is the value. It has a {@code build()} that returns what it
     * built, or fluent setters, which take a value and return the builder, and either a name ending in
     * {@code Builder} or a {@code build...} or {@code create...} method that returns something else.
     */
    private static boolean isBuilder(JavaClass javaClass) {
        List<JavaMethod> methods = javaClass.getAllMethods().stream()
                .filter(method -> !method.getModifiers().contains(JavaModifier.STATIC))
                .toList();
        boolean fluent = methods.stream().anyMatch(method -> !method.getRawParameterTypes().isEmpty()
                && method.getRawReturnType().equals(javaClass));
        return methods.stream().anyMatch(method -> "build".equals(method.getName()) && finishes(javaClass, method))
                || fluent && (javaClass.getSimpleName().endsWith("Builder") || methods.stream()
                        .anyMatch(method -> FINISHER.matcher(method.getName()).matches() && finishes(javaClass, method)));
    }

    private static boolean finishes(JavaClass builder, JavaMethod method) {
        return method.getRawParameterTypes().isEmpty() && !method.getRawReturnType().isEquivalentTo(void.class)
                && !method.getRawReturnType().equals(builder);
    }
}
