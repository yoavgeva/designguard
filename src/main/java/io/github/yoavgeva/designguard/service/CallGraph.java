package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
                calleesOf, (first, second) -> first));
        Set<String> reached = units.stream().filter(hasItself).map(JavaCodeUnit::getFullName)
                .collect(Collectors.toCollection(HashSet::new));
        boolean changed = true;
        while (changed) {
            List<String> callers = callees.entrySet().stream()
                    .filter(entry -> !reached.contains(entry.getKey()) && entry.getValue().stream().anyMatch(reached::contains))
                    .map(Map.Entry::getKey)
                    .toList();
            changed = reached.addAll(callers);
        }
        return Set.copyOf(reached);
    }

    /** The project code units the accesses may run, as {@code dispatch} resolves each target. */
    static Set<String> projectCallees(ProjectClasses classes, Stream<? extends JavaCodeUnitAccess<?>> accesses,
            Function<CodeUnitAccessTarget, Stream<String>> dispatch) {
        return accesses
                .map(JavaCodeUnitAccess::getTarget)
                .filter(target -> classes.isProject(target.getOwner()))
                .flatMap(dispatch)
                .collect(Collectors.toSet());
    }

    /** The target itself, plus every override in a project subclass: a call may dispatch to any of them. */
    static Stream<String> implementations(CodeUnitAccessTarget target) {
        Stream<String> overrides = target.getOwner().getAllSubclasses().stream()
                .flatMap(subclass -> subclass.getMethods().stream())
                .filter(method -> method.getName().equals(target.getName())
                        && method.getRawParameterTypes().equals(target.getRawParameterTypes()))
                .map(JavaCodeUnit::getFullName);
        return Stream.concat(Stream.of(target.getFullName()), overrides);
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
