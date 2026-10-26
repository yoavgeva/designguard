package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which parameters of the project's methods flow into a read of one record by its key: a Spring Data repository's
 * {@code findById}, {@code getById}, {@code getReferenceById} or {@code getOne}, an {@code EntityManager}'s
 * {@code find} or {@code getReference}, or MyBatis-Plus's {@code selectById} or {@code getById}, directly or through
 * project methods, an interface's implementations included ({@link ParameterFlows}). A method that checks the user
 * itself checks what it reads, so passes none on.
 */
final class KeyReads {

    private static final String REPOSITORY = "org.springframework.data.repository.Repository";
    private static final Set<String> REPOSITORY_FETCHES = Set.of("findById", "getById", "getReferenceById", "getOne");
    private static final Set<String> ENTITY_MANAGERS = Set.of("jakarta.persistence.EntityManager",
            "javax.persistence.EntityManager");
    private static final Set<String> ENTITY_MANAGER_FETCHES = Set.of("find", "getReference");
    private static final Set<String> MYBATIS = Set.of("com.baomidou.mybatisplus.core.mapper.BaseMapper",
            "com.baomidou.mybatisplus.extension.service.IService");
    private static final Set<String> MYBATIS_FETCHES = Set.of("selectById", "getById");

    private final Bytecode bytecode;
    private final Map<String, JavaClass> byName;
    /** For each method, by {@link #key}, its parameters that reach a read, with how a message names the read. */
    private final Map<String, Map<Integer, String>> found = new ConcurrentHashMap<>();

    /**
     * Finds the reads of the project's classes.
     *
     * @param classes the project's classes
     */
    KeyReads(ProjectClasses classes) {
        this.bytecode = classes.bytecode();
        this.byName = classes.stream().collect(Collectors.toMap(JavaClass::getName, type -> type, (first, second) -> first));
        List<JavaCodeUnit> units = CallGraph.unitsOf(classes.stream().toList()).stream()
                .filter(unit -> !(unit instanceof JavaMethod method && RequestHandlers.readsTheUser(method.getOwner(), method)))
                .sorted(Comparator.comparing(KeyReads::key))
                .toList();
        boolean changed = true;
        while (changed) {
            changed = units.stream().map(this::spreadInto).reduce(false, Boolean::logicalOr);
        }
    }

    /**
     * The read a flow leads to, as a message names it: the fetch itself, or the project method that reaches it and
     * the fetch, as {@code OrderService.find(), which calls OrderRepository.findById()} does.
     *
     * @param flow a parameter's flow into a call
     * @return the read, if the flow reaches one
     */
    Optional<String> describe(ParameterFlows.Flow flow) {
        Optional<JavaClass> owner = Optional.ofNullable(byName.get(flow.owner().replace('/', '.')));
        String called = simpleName(flow.owner()) + "." + flow.name() + "()";
        if (owner.map(type -> fetches(type, flow)).orElse(fetchesOutside(flow))) {
            return Optional.of(called);
        }
        return owner.stream()
                .flatMap(type -> Stream.concat(Stream.of(type), type.getAllSubclasses().stream()))
                .map(type -> found.getOrDefault(key(type, flow.name(), flow.descriptor()), Map.of()).get(flow.to()))
                .filter(read -> read != null)
                .findFirst()
                .map(read -> called + ", which calls " + read);
    }

    /** Adds the reads the method's flows newly reach; whether it found any. */
    private boolean spreadInto(JavaCodeUnit unit) {
        Map<Integer, String> reads = found.computeIfAbsent(key(unit), name -> new ConcurrentHashMap<>());
        boolean changed = false;
        for (ParameterFlows.Flow flow : flowsOf(unit)) {
            if (!reads.containsKey(flow.from())) {
                Optional<String> read = describe(flow);
                read.ifPresent(description -> reads.put(flow.from(), description));
                changed |= read.isPresent();
            }
        }
        return changed;
    }

    /** Whether a flow gives the key to a project repository's or mapper's read by key. */
    private static boolean fetches(JavaClass owner, ParameterFlows.Flow flow) {
        return flow.to() == 0 && (REPOSITORY_FETCHES.contains(flow.name()) && isA(owner, Set.of(REPOSITORY))
                || MYBATIS_FETCHES.contains(flow.name()) && isA(owner, MYBATIS));
    }

    /** Whether a flow gives the key to a library's read by key, as {@code EntityManager.find(type, id)} takes it. */
    private static boolean fetchesOutside(ParameterFlows.Flow flow) {
        return flow.to() == 1 && ENTITY_MANAGER_FETCHES.contains(flow.name())
                && ENTITY_MANAGERS.contains(flow.owner().replace('/', '.'));
    }

    private static boolean isA(JavaClass type, Set<String> supertypes) {
        return Stream.concat(Stream.of(type), Stream.concat(type.getAllRawSuperclasses().stream(),
                type.getAllRawInterfaces().stream())).anyMatch(candidate -> supertypes.contains(candidate.getName()));
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
    }

    private static String key(JavaCodeUnit unit) {
        return key(unit.getOwner(), unit.getName(), unit.getDescriptor());
    }

    private static String key(JavaClass owner, String name, String descriptor) {
        return owner.getName() + "." + name + descriptor;
    }

    /**
     * Which of a method's parameters flow into which arguments of its calls.
     *
     * @param unit a project method or constructor
     * @return the flows, in code order
     */
    List<ParameterFlows.Flow> flowsOf(JavaCodeUnit unit) {
        return bytecode.factsOf(unit.getOwner()).dataFlows().get().parameterFlows().getOrDefault(unit.getName() + unit.getDescriptor(), List.of());
    }
}
