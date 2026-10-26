package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import java.beans.Introspector;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which parameters of the project's methods are saved as they are: the parameter's object itself, not values made
 * from it, flows into a database client's write, as a repository's {@code save}, an {@code EntityManager}'s
 * {@code merge} or a mapper's {@code insert} or {@code update} is, or into a copy of all its properties, as
 * {@code BeanUtils.copyProperties(source, target)} makes, directly or through project methods ({@link ParameterFlows}).
 * Properties the code sets on the object on the way, as {@code form.setRole("USER")} does, are the code's.
 */
final class SavedObjects {

    private static final Pattern WRITES = Pattern.compile("(save|insert|update|persist|merge).*");
    private static final Set<String> MYBATIS_PLUS = Set.of("com.baomidou.mybatisplus.core.mapper.BaseMapper",
            "com.baomidou.mybatisplus.extension.service.IService");
    /** Copies of every property, by the copying class and method, with the position of the copied object. */
    private static final Map<String, Integer> COPIES = Map.of(
            "org.springframework.beans.BeanUtils.copyProperties", 0,
            "org.apache.commons.beanutils.BeanUtils.copyProperties", 1,
            "org.apache.commons.beanutils.PropertyUtils.copyProperties", 1,
            "cn.hutool.core.bean.BeanUtil.copyProperties", 0,
            "org.modelmapper.ModelMapper.map", 0);
    private static final String SETTER = "set";

    private final Bytecode bytecode;
    private final LibraryEffects libraries;
    private final Map<String, JavaClass> byName;
    /** For each method, by {@link #key}, its parameters saved as they are, with how and what the code sets on them. */
    private final Map<String, Map<Integer, Saved>> found = new ConcurrentHashMap<>();

    /**
     * How a parameter's object is saved.
     *
     * @param line        the line of the call that leads there
     * @param description how a message names the way there, as {@code Accounts.update(), which calls Forms.save()}
     * @param set         the properties the code sets on the object on the way
     */
    record Saved(int line, String description, Set<String> set) {

        Saved {
            set = Set.copyOf(set);
        }
    }

    /**
     * Finds the saves of the project's classes.
     *
     * @param classes the project's classes
     */
    SavedObjects(ProjectClasses classes) {
        this.bytecode = classes.bytecode();
        this.libraries = new LibraryEffects(classes);
        this.byName = classes.stream().collect(Collectors.toMap(JavaClass::getName, type -> type, (first, second) -> first));
        List<JavaCodeUnit> units = CallGraph.unitsOf(classes.stream().toList()).stream()
                .sorted(Comparator.comparing(SavedObjects::key))
                .toList();
        boolean changed = true;
        while (changed) {
            changed = units.stream().map(this::spreadInto).reduce(false, Boolean::logicalOr);
        }
    }

    /**
     * How a method's parameter is saved as it is, from the method's first call, by line, that leads there.
     *
     * @param unit     the method
     * @param position the parameter's position
     * @return how it's saved, with the properties the method itself sets on it too
     */
    Optional<Saved> of(JavaCodeUnit unit, int position) {
        return flowsOf(unit).stream()
                .filter(flow -> flow.from() == position && flow.whole() && flow.to() >= 0)
                .flatMap(flow -> saved(unit, flow).stream())
                .findFirst()
                .map(saved -> alsoSetting(saved, unit, position));
    }

    /** Adds the saves the method's flows newly reach; whether it found any. */
    private boolean spreadInto(JavaCodeUnit unit) {
        Map<Integer, Saved> saves = found.computeIfAbsent(key(unit), name -> new ConcurrentHashMap<>());
        boolean changed = false;
        for (ParameterFlows.Flow flow : flowsOf(unit)) {
            if (flow.whole() && flow.to() >= 0 && !saves.containsKey(flow.from())) {
                Optional<Saved> saved = saved(unit, flow).map(how -> alsoSetting(how, unit, flow.from()));
                saved.ifPresent(how -> saves.put(flow.from(), how));
                changed |= saved.isPresent();
            }
        }
        return changed;
    }

    /** How a whole object flowing into a call is saved: by the call itself, or by the project method it calls. */
    private Optional<Saved> saved(JavaCodeUnit unit, ParameterFlows.Flow flow) {
        String called = flow.owner().substring(flow.owner().lastIndexOf('/') + 1).replace('$', '.') + "." + flow.name()
                + "()";
        if (saves(unit, flow)) {
            return Optional.of(new Saved(flow.line(), called, Set.of()));
        }
        return Optional.ofNullable(byName.get(flow.owner().replace('/', '.'))).stream()
                .flatMap(type -> Stream.concat(Stream.of(type), type.getAllSubclasses().stream()))
                .map(type -> found.getOrDefault(key(type, flow.name(), flow.descriptor()), Map.of()).get(flow.to()))
                .filter(saved -> saved != null)
                .findFirst()
                .map(saved -> new Saved(flow.line(), called + ", which calls " + saved.description(), saved.set()));
    }

    /** Whether the flow's call saves what flows in: a database client's write, or a copy of all its properties. */
    private boolean saves(JavaCodeUnit unit, ParameterFlows.Flow flow) {
        String owner = flow.owner().replace('/', '.');
        Integer copied = COPIES.get(owner + "." + flow.name());
        if (copied != null) {
            return copied == flow.to();
        }
        return WRITES.matcher(flow.name()).matches() && unit.getCallsFromSelf().stream()
                .filter(call -> call.getLineNumber() == flow.line() && call.getName().equals(flow.name())
                        && call.getTargetOwner().getName().equals(owner))
                .map(JavaCodeUnitAccess::getTargetOwner)
                .anyMatch(target -> libraries.isClientType(target) || isA(target, MYBATIS_PLUS));
    }

    /** A save, with what the method sets on its parameter's object too. */
    private Saved alsoSetting(Saved saved, JavaCodeUnit unit, int position) {
        return new Saved(saved.line(), saved.description(), union(saved.set(), propertiesSetOn(unit, position)));
    }

    /** The properties the method sets on its parameter's object, as {@code form.setRole("USER")} sets {@code role}. */
    private Set<String> propertiesSetOn(JavaCodeUnit unit, int position) {
        return flowsOf(unit).stream()
                .filter(flow -> flow.from() == position && flow.whole() && flow.to() == -1
                        && flow.name().length() > SETTER.length() && flow.name().startsWith(SETTER))
                .map(flow -> Introspector.decapitalize(flow.name().substring(SETTER.length())))
                .collect(Collectors.toSet());
    }

    private List<ParameterFlows.Flow> flowsOf(JavaCodeUnit unit) {
        return bytecode.factsOf(unit.getOwner()).dataFlows().get().parameterFlows().getOrDefault(unit.getName() + unit.getDescriptor(), List.of());
    }

    private static boolean isA(JavaClass type, Set<String> supertypes) {
        return Stream.concat(Stream.of(type), Stream.concat(type.getAllRawSuperclasses().stream(),
                type.getAllRawInterfaces().stream())).anyMatch(candidate -> supertypes.contains(candidate.getName()));
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> both = new HashSet<>(first);
        both.addAll(second);
        return Set.copyOf(both);
    }

    private static String key(JavaCodeUnit unit) {
        return key(unit.getOwner(), unit.getName(), unit.getDescriptor());
    }

    private static String key(JavaClass owner, String name, String descriptor) {
        return owner.getName() + "." + name + descriptor;
    }
}
