package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds JPA entities updated from a value read before: a method that sets a property of an {@code @Entity} from its
 * own old value, as {@code product.setStock(product.getStock() - quantity)} does, or calls an entity's method that
 * does, as {@code product.decreaseStock(quantity)} doing {@code stock -= quantity} does. Two requests at once both read
 * the same value, and the second write undoes the first. It's flagged when the method, or one that calls it, writes:
 * it's {@code @Transactional}, so the change is flushed, or it surely writes to the database. It passes when the
 * entity has a {@code @Version} field, which makes the second write fail, or when the method or its caller reads the
 * row with a lock: a repository method annotated {@code @Lock}, or an {@code EntityManager} call given a
 * {@code LockModeType}.
 */
public final class LostUpdatesCheck {

    private static final String RULE = "no-lost-updates";
    private static final Set<String> ENTITIES = Set.of("jakarta.persistence.Entity", "javax.persistence.Entity");
    private static final Set<String> VERSIONS = Set.of("jakarta.persistence.Version", "javax.persistence.Version",
            "org.springframework.data.annotation.Version");
    private static final Set<String> TRANSACTIONAL = Set.of("org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional", "javax.transaction.Transactional");
    private static final String LOCK = "org.springframework.data.jpa.repository.Lock";
    private static final Set<String> LOCK_MODES = Set.of("jakarta.persistence.LockModeType", "javax.persistence.LockModeType");
    private static final String LOST = " from its old value: a request that changes it between the read and this write is lost";

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        Bytecode bytecode = classes.bytecode();
        SureEffects sure = code.serviceLike().sure();
        Map<String, JavaClass> byName = classes.stream()
                .collect(Collectors.toMap(JavaClass::getName, type -> type, (first, second) -> first));
        List<JavaCodeUnit> units = CallGraph.unitsOf(classes.stream().toList());
        Map<String, List<JavaCodeUnit>> callers = units.stream()
                .flatMap(unit -> unit.getCallsFromSelf().stream().map(call -> Map.entry(call.getTarget().getFullName(), unit)))
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        Function<String, Optional<JavaClass>> unversioned = owner -> Optional.ofNullable(byName.get(owner.replace('/', '.')))
                .filter(LostUpdatesCheck::isUnversionedEntity);
        Map<String, Mutator> mutators = units.stream()
                .flatMap(unit -> derivedWrites(bytecode, unit).stream()
                        .filter(write -> write.ofThis() && unversioned.apply(write.owner()).isPresent())
                        .limit(1)
                        .map(write -> Map.entry(unit.getFullName(), new Mutator(unit, write.property()))))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
        return units.stream()
                .filter(unit -> classes.selected(filter, unit.getOwner()) && classes.selected(filter, unit))
                .flatMap(unit -> sites(unit, bytecode, unversioned, mutators))
                .filter(site -> writes(site.unit(), callers, sure) && !locks(site.unit(), callers))
                .map(site -> classes.violation(RULE, site.unit().getOwner(), site.line(),
                        ProjectClasses.nameOf(site.unit().getOwner()) + "." + site.unit().getName() + "() "
                                + site.description() + LOST,
                        "add a @Version field to " + ProjectClasses.nameOf(site.entity()) + ", or change it in the "
                                + "database with an @Modifying UPDATE query, or read it with "
                                + "@Lock(LockModeType.PESSIMISTIC_WRITE)"))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /** Where a method updates an entity from its old value: itself, or by calling the entity's method that does. */
    private static Stream<Site> sites(JavaCodeUnit unit, Bytecode bytecode, Function<String, Optional<JavaClass>> unversioned,
            Map<String, Mutator> mutators) {
        Stream<Site> own = derivedWrites(bytecode, unit).stream()
                .filter(write -> !write.ofThis())
                .flatMap(write -> unversioned.apply(write.owner()).stream()
                        .map(entity -> new Site(unit, write.line(), entity, "sets " + ProjectClasses.nameOf(entity) + "."
                                + write.property())));
        Stream<Site> called = unit.getCallsFromSelf().stream()
                .flatMap(call -> Optional.ofNullable(mutators.get(call.getTarget().getFullName())).stream()
                        .map(mutator -> new Site(unit, call.getLineNumber(), mutator.unit().getOwner(), "calls "
                                + ProjectClasses.nameOf(mutator.unit().getOwner()) + "." + mutator.unit().getName()
                                + "(), which sets " + mutator.property())));
        return Stream.concat(own, called);
    }

    private static boolean isUnversionedEntity(JavaClass type) {
        return type.getAnnotations().stream().anyMatch(annotation -> ENTITIES.contains(annotation.getRawType().getName()))
                && type.getAllFields().stream().flatMap(field -> field.getAnnotations().stream())
                        .noneMatch(annotation -> VERSIONS.contains(annotation.getRawType().getName()));
    }

    /** Whether the method, or one that calls it, writes the change: it's transactional, or surely writes. */
    private static boolean writes(JavaCodeUnit unit, Map<String, List<JavaCodeUnit>> callers, SureEffects sure) {
        return around(unit, callers).anyMatch(each -> isTransactional(each)
                || each.getCallsFromSelf().stream().anyMatch(sure::writesToTheDatabase));
    }

    /** Whether the method, or one that calls it, reads with a lock. */
    private static boolean locks(JavaCodeUnit unit, Map<String, List<JavaCodeUnit>> callers) {
        return around(unit, callers).anyMatch(each -> each.getCallsFromSelf().stream().anyMatch(LostUpdatesCheck::locks));
    }

    private static boolean locks(JavaCodeUnitAccess<?> call) {
        return "setLockMode".equals(call.getName())
                || call.getTarget().getRawParameterTypes().stream().anyMatch(type -> LOCK_MODES.contains(type.getName()))
                || call.getTarget().resolveMember().stream()
                        .anyMatch(member -> member.getAnnotations().stream()
                                .anyMatch(annotation -> LOCK.equals(annotation.getRawType().getName())));
    }

    private static Stream<JavaCodeUnit> around(JavaCodeUnit unit, Map<String, List<JavaCodeUnit>> callers) {
        return Stream.concat(Stream.of(unit), callers.getOrDefault(unit.getFullName(), List.of()).stream());
    }

    private static boolean isTransactional(JavaCodeUnit unit) {
        return Stream.concat(unit.getAnnotations().stream(), unit.getOwner().getAnnotations().stream())
                .anyMatch(annotation -> TRANSACTIONAL.contains(annotation.getRawType().getName()));
    }

    private static List<DerivedWrites.DerivedWrite> derivedWrites(Bytecode bytecode, JavaCodeUnit unit) {
        return bytecode.factsOf(unit.getOwner()).derivedWrites().getOrDefault(unit.getName() + unit.getDescriptor(), List.of());
    }

    /** An entity's method that writes one of its properties from its old value. */
    private record Mutator(JavaCodeUnit unit, String property) {
    }

    /** Where a method updates an entity from its old value, and how the message says so. */
    private record Site(JavaCodeUnit unit, int line, JavaClass entity, String description) {
    }
}
