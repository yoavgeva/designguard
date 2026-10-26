package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Which interfaces and abstract classes a call can reach the effects of their implementations through. */
final class EffectfulAbstractions {

    private final Set<String> effectfulUnits;
    private final Set<String> ioEffectfulUnits;

    /**
     * Judges abstractions by the full names of the effectful methods, constructors and lambdas, and of those among them
     * that do more than read the clock or randomness.
     */
    EffectfulAbstractions(Set<String> effectfulUnits, Set<String> ioEffectfulUnits) {
        this.effectfulUnits = Set.copyOf(effectfulUnits);
        this.ioEffectfulUnits = Set.copyOf(ioEffectfulUnits);
    }

    /**
     * The classes, plus the interfaces and abstract classes one of them extends that isn't safe to create or call: it
     * implements one of the abstraction's methods with effectful code, does I/O anywhere, as when loading keys at
     * start-up or storing a deferred query, or holds a service or resource. An implementation that reads the clock or
     * randomness only in methods of its own, as one model among many stamping a token does, leaves the abstraction a
     * value; so does a functional interface, which passes behaviour as data.
     */
    Set<String> with(List<JavaClass> candidates, Set<String> concrete, Predicate<JavaClass> holdsAService) {
        Set<String> result = new HashSet<>(concrete);
        candidates.stream()
                .filter(type -> (type.isInterface() || type.getModifiers().contains(JavaModifier.ABSTRACT))
                        && !Hierarchy.isFunctionalInterface(type))
                .filter(type -> reachesEffects(type, concrete, holdsAService))
                .map(JavaClass::getName)
                .forEach(result::add);
        return result;
    }

    private boolean reachesEffects(JavaClass abstraction, Set<String> concrete, Predicate<JavaClass> holdsAService) {
        Set<String> signatures = abstraction.getAllMethods().stream().map(EffectfulAbstractions::signature)
                .collect(Collectors.toSet());
        return abstraction.getAllSubclasses().stream()
                .filter(subclass -> concrete.contains(subclass.getName()))
                .anyMatch(subclass -> holdsAService.test(subclass) || doesIo(subclass)
                        || subclass.getAllMethods().stream().anyMatch(method -> effectfulUnits.contains(method.getFullName())
                                && signatures.contains(signature(method))));
    }

    private boolean doesIo(JavaClass type) {
        return Stream.of(type.getAllMethods().stream(), type.getConstructors().stream(), type.getStaticInitializer().stream())
                .flatMap(units -> units)
                .anyMatch(unit -> ioEffectfulUnits.contains(unit.getFullName()));
    }

    private static String signature(JavaMethod method) {
        return method.getName() + method.getRawParameterTypes().stream().map(JavaClass::getName).toList();
    }
}
