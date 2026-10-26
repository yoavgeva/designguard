package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Spreads a property of code units, such as touching the outside world, to every project code unit that calls one. */
final class CallGraph {

    private CallGraph() {
    }

    /**
     * The units that have the property themselves, plus every unit that calls one, until none is added.
     *
     * @param units     the code units to consider
     * @param calleesOf each unit's project callees, by full name
     * @param hasItself whether a unit has the property without calling anything
     * @return the full names of the units with the property
     */
    static Set<String> reaching(List<JavaCodeUnit> units, Function<JavaCodeUnit, Set<String>> calleesOf,
            Predicate<JavaCodeUnit> hasItself) {
        Map<String, Set<String>> callees = units.stream().collect(Collectors.toMap(JavaCodeUnit::getFullName,
                calleesOf, CallGraph::union));
        return spread(callees, units.stream().filter(hasItself).map(JavaCodeUnit::getFullName).collect(Collectors.toSet()));
    }

    /**
     * The seeds, plus every caller of a name already reached, until none is added. Each call is followed once, from
     * callee to caller, so the time grows with the number of calls, however deep the chains.
     *
     * @param callees each caller's callees, by full name
     * @param seeds   the names that have the property themselves
     * @return the names with the property
     */
    static Set<String> spread(Map<String, Set<String>> callees, Set<String> seeds) {
        Map<String, List<String>> callers = callees.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(callee -> Map.entry(callee, entry.getKey())))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        Set<String> reached = new HashSet<>(seeds);
        Deque<String> pending = new ArrayDeque<>(seeds);
        while (!pending.isEmpty()) {
            for (String caller : callers.getOrDefault(pending.pop(), List.of())) {
                if (reached.add(caller)) {
                    pending.push(caller);
                }
            }
        }
        return Set.copyOf(reached);
    }

    /**
     * The seeds, plus every caller with a call whose possible targets are all reached, until none is added. A call
     * that may run a unit without the property, such as a strategy with implementations of both kinds, doesn't spread
     * it. Each target of each call is counted once, so the time grows with the number of calls.
     *
     * @param calls each caller's calls, each as the full names of the units it may run; a call with none never spreads
     * @param seeds the names that have the property themselves
     * @return the names with the property
     */
    static Set<String> spreadThroughEvery(Map<String, Set<Set<String>>> calls, Set<String> seeds) {
        List<String> callers = new ArrayList<>();
        List<Set<String>> targets = new ArrayList<>();
        calls.forEach((caller, made) -> made.stream().filter(call -> !call.isEmpty()).forEach(call -> {
            callers.add(caller);
            targets.add(call);
        }));
        int[] unreached = targets.stream().mapToInt(Set::size).toArray();
        Map<String, List<Integer>> callsOf = IntStream.range(0, targets.size()).boxed()
                .flatMap(call -> targets.get(call).stream().map(target -> Map.entry(target, call)))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        Set<String> reached = new HashSet<>(seeds);
        Deque<String> pending = new ArrayDeque<>(seeds);
        while (!pending.isEmpty()) {
            for (int call : callsOf.getOrDefault(pending.pop(), List.of())) {
                unreached[call]--;
                if (unreached[call] == 0 && reached.add(callers.get(call))) {
                    pending.push(callers.get(call));
                }
            }
        }
        return Set.copyOf(reached);
    }

    /**
     * Both units' callees: units can share a full name, as a covariant override and the bridge method the compiler
     * adds for it do, and a call by that name may run either.
     */
    private static Set<String> union(Set<String> first, Set<String> second) {
        return Stream.concat(first.stream(), second.stream()).collect(Collectors.toSet());
    }

    /** The project code units the accesses may run, as {@code dispatch} resolves each access. */
    static Set<String> projectCallees(ProjectClasses classes, Stream<? extends JavaCodeUnitAccess<?>> accesses,
            Function<JavaCodeUnitAccess<?>, Set<String>> dispatch) {
        return accesses
                .filter(access -> classes.isProject(access.getTarget().getOwner()))
                .flatMap(access -> dispatch.apply(access).stream())
                .collect(Collectors.toSet());
    }

    /** Full names of the code units with a body: all but abstract methods. */
    static Set<String> withBodies(List<JavaCodeUnit> units) {
        return units.stream()
                .filter(unit -> !unit.getModifiers().contains(JavaModifier.ABSTRACT))
                .map(JavaCodeUnit::getFullName)
                .collect(Collectors.toSet());
    }

    /** Every method, constructor and static initializer of the classes. */
    static List<JavaCodeUnit> unitsOf(List<JavaClass> classes) {
        return classes.stream().flatMap(type -> type.getCodeUnits().stream()).toList();
    }

    /** Names of the classes with at least one of the code units. */
    static Set<String> ownersOf(List<JavaClass> classes, Set<String> codeUnits) {
        return classes.stream()
                .filter(type -> type.getCodeUnits().stream().anyMatch(unit -> codeUnits.contains(unit.getFullName())))
                .map(JavaClass::getName)
                .collect(Collectors.toSet());
    }
}
