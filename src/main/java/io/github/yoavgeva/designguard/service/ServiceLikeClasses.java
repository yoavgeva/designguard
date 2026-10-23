package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Classifies project classes by what their code does, using {@link Effects}.
 *
 * <p>A class is <em>effectful</em> if any of its methods, constructors or lambdas touches the outside world. A class
 * is <em>service-like</em>, worth depending on through an interface, if it is effectful, holds a field of an impure
 * JDK type or of a service-like class, or is an abstraction with a service-like implementation. Everything else is a
 * value. Exceptions and annotations are never service-like.
 */
final class ServiceLikeClasses {

    private final Overrides overrides = new Overrides();
    private final Effects effects;
    private final Set<String> effectfulNames;
    private final Memo<Set<String>> effectfulWhenRun;
    private final Memo<Set<String>> ownEffectsWhenRun;
    private final Memo<Set<String>> effectfulOnTheirOwnNames;
    private final Set<String> serviceLikeNames;

    ServiceLikeClasses(ProjectClasses classes, List<String> extraImpurePackages) {
        this.effects = new Effects(extraImpurePackages, overrides);
        List<JavaClass> candidates = classes.stream().filter(ServiceLikeClasses::canBeServiceLike).toList();
        this.effectfulNames = Set.copyOf(withAbstractions(candidates,
                CallGraph.ownersOf(candidates, effects.effectfulCodeUnits(classes, candidates))));
        this.effectfulWhenRun = new Memo<>(() -> effects.effectfulWhenRun(classes, candidates));
        this.ownEffectsWhenRun = new Memo<>(() -> effects.ownEffectsWhenRun(classes, candidates));
        this.effectfulOnTheirOwnNames = new Memo<>(() -> Set.copyOf(effects.effectfulOnTheirOwn(classes, candidates)));
        this.serviceLikeNames = Set.copyOf(serviceLike(candidates));
    }

    /** Whether the class is worth depending on through an interface. */
    boolean contains(JavaClass javaClass) {
        return serviceLikeNames.contains(javaClass.getName());
    }

    /** Whether the class's own code touches the outside world, directly or through project code it calls. */
    boolean isEffectful(JavaClass javaClass) {
        return effectfulNames.contains(javaClass.getName());
    }

    /**
     * Whether the class's own code touches the outside world, not counting what implementations it is given do
     * behind interfaces and abstract methods. Creating such a class hard-wires its effects.
     */
    boolean isEffectfulOnItsOwn(JavaClass javaClass) {
        return effectfulOnTheirOwnNames.get().contains(javaClass.getName());
    }

    /**
     * Whether making the call touches the outside world right away: it calls an outside-world API, or project code,
     * any override of it included, whose own calls do. Lambdas the code creates don't count until they run.
     */
    boolean runsEffects(CodeUnitAccessTarget target) {
        Set<String> whenRun = effectfulWhenRun.get();
        return effects.isImpure(target) || overrides.of(target).stream().anyMatch(whenRun::contains);
    }

    /**
     * Whether running the method touches the outside world through calls it makes itself, not through implementations
     * it is given or lambdas it creates.
     */
    boolean hasOwnEffects(JavaCodeUnit unit) {
        return ownEffectsWhenRun.get().contains(unit.getFullName());
    }

    static boolean canBeServiceLike(JavaClass javaClass) {
        return Effects.canHaveEffects(javaClass);
    }

    private Set<String> serviceLike(List<JavaClass> candidates) {
        Set<String> result = new HashSet<>(effectfulNames);
        candidates.stream()
                .filter(type -> type.getFields().stream()
                        .flatMap(field -> field.getAllInvolvedRawTypes().stream())
                        .anyMatch(effects::isImpureType))
                .map(JavaClass::getName)
                .forEach(result::add);
        boolean changed = true;
        while (changed) {
            List<String> reached = candidates.stream()
                    .filter(type -> !result.contains(type.getName()) && holdsServiceLike(type, result))
                    .map(JavaClass::getName)
                    .toList();
            changed = result.addAll(reached);
        }
        return withAbstractions(candidates, result);
    }

    private static boolean holdsServiceLike(JavaClass type, Set<String> serviceLike) {
        return type.getFields().stream()
                .flatMap(field -> field.getAllInvolvedRawTypes().stream())
                .anyMatch(fieldType -> !fieldType.equals(type) && serviceLike.contains(fieldType.getName()));
    }

    private static Set<String> withAbstractions(List<JavaClass> candidates, Set<String> concrete) {
        Set<String> result = new HashSet<>(concrete);
        candidates.stream()
                .filter(type -> type.isInterface() || type.getModifiers().contains(JavaModifier.ABSTRACT))
                .filter(type -> type.getAllSubclasses().stream().anyMatch(subclass -> concrete.contains(subclass.getName())))
                .map(JavaClass::getName)
                .forEach(result::add);
        return result;
    }
}
