package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds service-like classes that hold changing state: a non-final instance field, or a final one holding a mutable
 * collection, atomic or string builder, by its type or by what a constructor assigns it. A service is shared by its
 * callers, so its state leaks between them and races under concurrency. A non-final field a container injects, as
 * {@code @Autowired}, {@code @Value}, {@code @Inject}, {@code @Resource} and {@code @PersistenceContext} do, holds a
 * collaborator or setting rather than state, so it is reported as one to take in the constructor. Wiring code, enums,
 * records and what a framework creates for each use, as a command for each run, are skipped.
 */
public final class StatelessServicesCheck {

    private static final String RULE = "stateless-services";
    private static final String KEEP_STATE_OUT = "keep state out of services: pass it in and return it as values, or keep"
            + " it in a store the service calls";
    private static final Set<String> MUTABLE_TYPES = Set.of("java.util.ArrayList", "java.util.LinkedList",
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.HashSet",
            "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.ArrayDeque", "java.util.PriorityQueue",
            "java.util.Vector", "java.util.Hashtable", "java.util.Stack", "java.util.EnumMap", "java.util.IdentityHashMap",
            "java.util.WeakHashMap", "java.util.concurrent.ConcurrentHashMap", "java.util.concurrent.ConcurrentLinkedQueue",
            "java.util.concurrent.ConcurrentLinkedDeque", "java.util.concurrent.ConcurrentSkipListMap",
            "java.util.concurrent.ConcurrentSkipListSet", "java.util.concurrent.CopyOnWriteArrayList",
            "java.util.concurrent.CopyOnWriteArraySet", "java.util.concurrent.LinkedBlockingQueue",
            "java.util.concurrent.ArrayBlockingQueue", "java.util.concurrent.atomic.AtomicInteger",
            "java.util.concurrent.atomic.AtomicLong", "java.util.concurrent.atomic.AtomicBoolean",
            "java.util.concurrent.atomic.AtomicReference", "java.util.concurrent.atomic.LongAdder",
            "java.lang.StringBuilder", "java.lang.StringBuffer");

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and message
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        ServiceLikeClasses serviceLike = code.serviceLike();
        WiringCode wiring = code.wiring();
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && serviceLike.contains(type) && Hierarchy.isConcrete(type)
                        && !type.isEnum() && !type.isRecord() && !wiring.contains(type)
                        && !ManagedComponents.isCreatedPerUse(type))
                .flatMap(type -> type.getFields().stream()
                        .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC)
                                && !field.getModifiers().contains(JavaModifier.SYNTHETIC)
                                && classes.selected(filter, field))
                        .flatMap(field -> finding(field).stream()
                                .map(finding -> classes.violation(RULE, type, 0, ProjectClasses.nameOf(type)
                                        + " is service-like, but its " + finding.what(), finding.fix()))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Optional<Finding> finding(JavaField field) {
        if (!field.getModifiers().contains(JavaModifier.FINAL) && WiringCode.isInjected(field)) {
            return Optional.of(new Finding("injected field '" + field.getName() + "' isn't final",
                    WiringCode.INJECT_IN_CONSTRUCTOR));
        }
        return state(field).map(state -> new Finding("field '" + field.getName() + "' " + state, KEEP_STATE_OUT));
    }

    /** What makes the field changing state, if anything. */
    private static Optional<String> state(JavaField field) {
        if (!field.getModifiers().contains(JavaModifier.FINAL)) {
            return Optional.of("isn't final");
        }
        if (field.getRawType().isArray()) {
            return Optional.of("holds an array");
        }
        return Stream.concat(Stream.of(field.getRawType()), assignedInConstructors(field))
                .filter(type -> MUTABLE_TYPES.contains(type.getName()))
                .findFirst()
                .map(type -> "holds a mutable " + type.getSimpleName());
    }

    /** The classes constructors create on the lines where they assign the field, as in {@code = new ArrayList<>()}. */
    private static Stream<JavaClass> assignedInConstructors(JavaField field) {
        return field.getAccessesToSelf().stream()
                .filter(access -> access.getAccessType() == JavaFieldAccess.AccessType.SET
                        && JavaConstructor.CONSTRUCTOR_NAME.equals(access.getOrigin().getName()))
                .flatMap(access -> access.getOrigin().getConstructorCallsFromSelf().stream()
                        .filter(call -> call.getLineNumber() == access.getLineNumber())
                        .map(JavaAccess::getTargetOwner));
    }

    /** What is wrong with a field, and how to fix it. */
    private record Finding(String what, String fix) {
    }
}
