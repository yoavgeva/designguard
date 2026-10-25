package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Data that only reads the clock or randomness: classes service-like only because their own code does, as a request
 * that stamps a UUID on what it builds or a stopwatch does, which hold data in fields and do no I/O. They are no shared
 * service: their state, creating them and depending on them directly are fine, and the clock and randomness rules
 * report the read itself. Tests may still mock them, to control what they read.
 */
final class TimeOrChanceData {

    /** JDK types that carry behaviour rather than data. */
    private static final List<String> BEHAVIOUR = List.of("java.lang.Runnable", "java.util.concurrent.Callable",
            "java.util.concurrent.Executor", "java.util.concurrent.ThreadFactory", "java.lang.Thread",
            "java.lang.ClassLoader", "java.lang.Process");

    private final Predicate<JavaClass> isProject;
    private final Predicate<JavaClass> isImpureType;
    private final Set<String> serviceLike;
    private final Set<String> ioEffectfulUnits;

    /**
     * Judges classes by what the first classification made service-like and by the units that do I/O.
     *
     * @param isProject        whether a type is the project's
     * @param isImpureType     whether using a type touches the outside world
     * @param serviceLike      the service-like classes, by name
     * @param ioEffectfulUnits full names of the units that do more than read the clock or randomness
     */
    TimeOrChanceData(Predicate<JavaClass> isProject, Predicate<JavaClass> isImpureType, Set<String> serviceLike,
            Set<String> ioEffectfulUnits) {
        this.isProject = isProject;
        this.isImpureType = isImpureType;
        this.serviceLike = serviceLike;
        this.ioEffectfulUnits = ioEffectfulUnits;
    }

    /** The service-like classes among the candidates that are such data, by name; data may hold other such data. */
    Set<String> among(List<JavaClass> candidates) {
        List<JavaClass> possible = candidates.stream()
                .filter(type -> serviceLike.contains(type.getName()) && !type.isInterface() && !doesIo(type)
                        && !instanceFields(type).isEmpty())
                .toList();
        Set<String> data = new HashSet<>();
        boolean changed = true;
        while (changed) {
            List<String> found = possible.stream()
                    .filter(type -> !data.contains(type.getName()) && holdsOnlyData(type, data))
                    .map(JavaClass::getName)
                    .toList();
            changed = data.addAll(found);
        }
        return Set.copyOf(data);
    }

    private boolean doesIo(JavaClass type) {
        return Stream.of(type.getAllMethods().stream(), type.getConstructors().stream(), type.getStaticInitializer().stream())
                .flatMap(units -> units)
                .anyMatch(unit -> ioEffectfulUnits.contains(unit.getFullName()));
    }

    private boolean holdsOnlyData(JavaClass type, Set<String> data) {
        return instanceFields(type).stream()
                .flatMap(field -> field.getAllInvolvedRawTypes().stream())
                .allMatch(held -> held.equals(type) || isData(held, data));
    }

    /** Whether values of the type are data: primitives, enums, plain JDK values and collections, and project values. */
    private boolean isData(JavaClass type, Set<String> data) {
        if (type.isArray()) {
            return isData(type.getComponentType(), data);
        }
        if (type.isPrimitive() || type.isEnum()) {
            return true;
        }
        if (isProject.test(type)) {
            return !serviceLike.contains(type.getName()) || data.contains(type.getName());
        }
        boolean jdk = type.getName().startsWith("java.") || type.getName().startsWith("javax.");
        return jdk && !isImpureType.test(type) && !"java.util.function".equals(type.getPackageName())
                && BEHAVIOUR.stream().noneMatch(type::isAssignableTo);
    }

    private static List<JavaField> instanceFields(JavaClass type) {
        return type.getAllFields().stream()
                .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC)
                        && !field.getModifiers().contains(JavaModifier.SYNTHETIC))
                .toList();
    }
}
