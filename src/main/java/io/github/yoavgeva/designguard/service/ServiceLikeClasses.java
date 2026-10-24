package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Classifies project classes by what their code does, using {@link Effects}.
 *
 * <p>A class is <em>effectful</em> if any of its methods, constructors or lambdas touches the outside world. A class
 * is <em>service-like</em>, worth depending on through an interface, if it is effectful, holds a field of an impure
 * JDK type or of a service-like class, or is an abstraction with a service-like implementation. Everything else is a
 * value. Exceptions and annotations are never service-like, and neither are persistent classes, which a store loads
 * and saves as data: JPA entities, embeddables and mapped superclasses, and documents. What their code does, such as
 * reading the clock for a creation date, is reported where it happens.
 */
final class ServiceLikeClasses {

    private static final Set<String> PERSISTENT = Set.of("Entity", "Embeddable", "MappedSuperclass", "Document");
    private final Overrides overrides = new Overrides();
    private final ProjectClasses classes;
    private final Effects effects;
    private final Set<String> effectfulNames;
    private final Memo<Set<String>> withBodies;
    private final Memo<Set<String>> surelyEffectfulWhenRun;
    private final Memo<Set<String>> ownEffectsWhenRun;
    private final Memo<Set<String>> effectfulOnTheirOwnNames;
    private final Set<String> serviceLikeNames;

    ServiceLikeClasses(ProjectClasses classes, List<String> extraImpurePackages) {
        this.classes = classes;
        this.effects = new Effects(extraImpurePackages, overrides);
        List<JavaClass> candidates = classes.stream().filter(ServiceLikeClasses::canBeServiceLike).toList();
        this.effectfulNames = Set.copyOf(withAbstractions(candidates,
                CallGraph.ownersOf(candidates, effects.effectfulCodeUnits(classes, candidates))));
        this.withBodies = new Memo<>(() -> CallGraph.withBodies(CallGraph.unitsOf(candidates)));
        this.surelyEffectfulWhenRun = new Memo<>(() -> effects.surelyEffectfulWhenRun(classes, candidates));
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
     * Whether making the call surely touches the outside world right away: it calls an outside-world API, or project
     * code whose every implementation's own calls do. A strategy with an implementation without effects doesn't, nor
     * does a JDK interface such as {@code Iterator.hasNext()} that a project class implements with I/O. Lambdas the
     * code creates don't count until they run.
     */
    boolean runsEffects(JavaCodeUnitAccess<?> call) {
        if (effects.isImpure(classes.bytecode(), call)) {
            return true;
        }
        Set<String> implementations = classes.isProject(call.getTargetOwner())
                ? overrides.implementationsOf(classes.bytecode(), call, withBodies.get())
                : Set.of();
        return !implementations.isEmpty() && surelyEffectfulWhenRun.get().containsAll(implementations);
    }

    /**
     * Whether running the method touches the outside world through calls it makes itself, not through implementations
     * it is given or lambdas it creates.
     */
    boolean hasOwnEffects(JavaCodeUnit unit) {
        return ownEffectsWhenRun.get().contains(unit.getFullName());
    }

    static boolean canBeServiceLike(JavaClass javaClass) {
        return JdkEffects.canHaveEffects(javaClass);
    }

    /** Whether the class is persistent data: a JPA entity, embeddable or mapped superclass, or a document. */
    static boolean isPersistent(JavaClass javaClass) {
        return ProjectClasses.annotationNames(javaClass).stream().anyMatch(PERSISTENT::contains);
    }

    /**
     * The effectful classes, those holding an impure resource or a service-like class, and the subclasses of those
     * whose own code or fields make them service-like, which inherit both, until none is added; then the interfaces and
     * abstract classes with such a subclass. A class whose code is effectful only through overrides, as a base class
     * calling its own abstract method is, passes nothing on to its subclasses. Persistent classes are left out.
     */
    private Set<String> serviceLike(List<JavaClass> candidates) {
        List<JavaClass> services = candidates.stream().filter(type -> !isPersistent(type)).toList();
        Set<String> result = namesAmong(services, effectfulNames);
        Set<String> inherited = namesAmong(services, effectfulOnTheirOwnNames.get());
        services.stream()
                .filter(type -> type.getFields().stream()
                        .flatMap(field -> field.getAllInvolvedRawTypes().stream())
                        .anyMatch(effects::isImpureType))
                .map(JavaClass::getName)
                .forEach(name -> {
                    result.add(name);
                    inherited.add(name);
                });
        boolean changed = true;
        while (changed) {
            List<String> reached = services.stream()
                    .filter(type -> !result.contains(type.getName())
                            && (holdsServiceLike(type, result) || extendsServiceLike(type, inherited)))
                    .map(JavaClass::getName)
                    .toList();
            inherited.addAll(reached);
            changed = result.addAll(reached);
        }
        return withAbstractions(services, result);
    }

    private static Set<String> namesAmong(List<JavaClass> types, Set<String> names) {
        return types.stream().map(JavaClass::getName).filter(names::contains).collect(Collectors.toCollection(HashSet::new));
    }

    private static boolean extendsServiceLike(JavaClass type, Set<String> serviceLike) {
        return type.getAllRawSuperclasses().stream().anyMatch(superclass -> serviceLike.contains(superclass.getName()));
    }

    private static boolean holdsServiceLike(JavaClass type, Set<String> serviceLike) {
        return type.getFields().stream()
                .flatMap(field -> field.getAllInvolvedRawTypes().stream())
                .anyMatch(fieldType -> !fieldType.equals(type) && serviceLike.contains(fieldType.getName()));
    }

    /**
     * The classes, plus the interfaces and abstract classes one of them extends; a functional interface passes
     * behaviour as data, so an effectful implementation doesn't make it service-like.
     */
    private static Set<String> withAbstractions(List<JavaClass> candidates, Set<String> concrete) {
        Set<String> result = new HashSet<>(concrete);
        candidates.stream()
                .filter(type -> (type.isInterface() || type.getModifiers().contains(JavaModifier.ABSTRACT))
                        && !Hierarchy.isFunctionalInterface(type))
                .filter(type -> type.getAllSubclasses().stream().anyMatch(subclass -> concrete.contains(subclass.getName())))
                .map(JavaClass::getName)
                .forEach(result::add);
        return result;
    }
}
