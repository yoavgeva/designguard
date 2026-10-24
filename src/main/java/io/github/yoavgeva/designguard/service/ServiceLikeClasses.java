package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
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
 * and saves as data: JPA entities, embeddables and mapped superclasses, and documents, nor data that only reads the
 * clock or randomness ({@link TimeOrChanceData}). What their code does, such as reading the clock for a creation date,
 * is reported where it happens.
 */
final class ServiceLikeClasses {

    private static final Set<String> PERSISTENT = Set.of("Entity", "Embeddable", "MappedSuperclass", "Document");
    private final Overrides overrides = new Overrides();
    private final Effects effects;
    private final EffectfulAbstractions abstractions;
    private final Set<String> effectfulOwners;
    private final Set<String> effectfulNames;
    private final Set<String> timeOrChanceData;
    private final SureEffects sureEffects;
    private final Memo<Set<String>> ownEffectsWhenRun;
    private final Memo<Set<String>> effectfulOnTheirOwnNames;
    private final Set<String> serviceLikeNames;

    ServiceLikeClasses(ProjectClasses classes, List<String> extraImpurePackages) {
        this.effects = new Effects(extraImpurePackages, overrides, classes);
        List<JavaClass> candidates = classes.stream().filter(ServiceLikeClasses::canBeServiceLike).toList();
        Set<String> effectfulUnits = effects.effectfulCodeUnits(classes, candidates);
        Set<String> ioEffectfulUnits = effects.ioEffectfulCodeUnits(classes, candidates);
        this.abstractions = new EffectfulAbstractions(effectfulUnits, ioEffectfulUnits);
        this.effectfulOwners = Set.copyOf(CallGraph.ownersOf(candidates, effectfulUnits));
        this.effectfulNames = Set.copyOf(abstractions.with(candidates, effectfulOwners, this::holdsAResource));
        this.sureEffects = new SureEffects(classes, effects, overrides, candidates);
        this.ownEffectsWhenRun = new Memo<>(() -> effects.ownEffectsWhenRun(classes, candidates));
        this.effectfulOnTheirOwnNames = new Memo<>(() -> Set.copyOf(effects.effectfulOnTheirOwn(classes, candidates)));
        Set<String> first = serviceLike(candidates, Set.of());
        this.timeOrChanceData = new TimeOrChanceData(classes::isProject, effects::isImpureType, first, ioEffectfulUnits)
                .among(candidates);
        this.serviceLikeNames = Set.copyOf(timeOrChanceData.isEmpty() ? first : serviceLike(candidates, timeOrChanceData));
    }

    /** Whether the class is worth depending on through an interface. */
    boolean contains(JavaClass javaClass) {
        return serviceLikeNames.contains(javaClass.getName());
    }

    /**
     * Whether the class is data that only reads the clock or randomness, as a request stamping a UUID on what it builds
     * is: not service-like, but worth mocking to control what it reads. See {@link TimeOrChanceData}.
     */
    boolean isTimeOrChanceData(JavaClass javaClass) {
        return timeOrChanceData.contains(javaClass.getName());
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

    /** Which calls surely touch the outside world, or surely do I/O, when they are made. */
    SureEffects sure() {
        return sureEffects;
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
     * calling its own abstract method is, passes nothing on to its subclasses. Persistent classes and the excluded ones
     * are left out.
     */
    private Set<String> serviceLike(List<JavaClass> candidates, Set<String> excluded) {
        List<JavaClass> services = candidates.stream()
                .filter(type -> !isPersistent(type) && !excluded.contains(type.getName()))
                .toList();
        Set<String> result = new HashSet<>(abstractions.with(services, namesAmong(services, effectfulOwners),
                this::holdsAResource));
        Set<String> inherited = namesAmong(services, effectfulOnTheirOwnNames.get());
        services.stream()
                .filter(this::holdsAResource)
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
        return abstractions.with(services, result, type -> holdsAResource(type) || holdsServiceLike(type, result));
    }

    private boolean holdsAResource(JavaClass type) {
        return type.getFields().stream()
                .flatMap(field -> field.getAllInvolvedRawTypes().stream())
                .anyMatch(effects::isImpureType);
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
}
