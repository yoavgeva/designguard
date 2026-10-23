package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks package-by-feature slices: a slice may use another slice only through that slice's top-level package (its
 * API), never its subpackages (its internals), and slices must not depend on each other in a cycle.
 */
public final class FeatureSliceCheck {

    private static final String RULE = "feature-slices";

    /** The packages directly above the slices, given the checked packages. */
    private final UnaryOperator<List<String>> slicePrefixes;

    /** Configures the check with slices directly below each checked package. */
    public FeatureSliceCheck() {
        this.slicePrefixes = UnaryOperator.identity();
    }

    /**
     * Configures the check with slices directly below one package.
     *
     * @param slicePrefix the package directly above the slices, e.g. {@code com.shop} for {@code com.shop.orders}
     */
    public FeatureSliceCheck(String slicePrefix) {
        List<String> prefixes = List.of(slicePrefix);
        this.slicePrefixes = checkedPackages -> prefixes;
    }

    /**
     * Finds slice violations from the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        List<String> prefixes = slicePrefixes.apply(code.settings().packages());
        List<Crossing> crossings = classes.stream()
                .filter(origin -> classes.selected(filter, origin))
                .flatMap(classes.bytecode()::dependenciesFrom)
                .filter(dependency -> classes.isProject(dependency.target()))
                .flatMap(dependency -> crossing(prefixes, dependency).stream())
                .toList();
        return Stream.concat(internals(classes, crossings), cycles(classes, crossings))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Stream<Violation> internals(ProjectClasses classes, List<Crossing> crossings) {
        return crossings.stream()
                .filter(crossing -> !crossing.target().getPackageName().equals(crossing.targetSlice().rootPackage()))
                .collect(Collectors.toMap(crossing -> crossing.origin().getName() + ">" + crossing.target().getName(),
                        crossing -> crossing, (first, second) -> first.line() <= second.line() ? first : second,
                        LinkedHashMap::new))
                .values().stream()
                .map(crossing -> classes.violation(RULE, crossing.origin(), crossing.line(),
                        ProjectClasses.nameOf(crossing.origin()) + " (slice " + crossing.originSlice().name() + ") uses "
                                + ProjectClasses.nameOf(crossing.target()) + ", which is internal to slice "
                                + crossing.targetSlice().name(),
                        "use " + crossing.targetSlice().name() + "'s API in package "
                                + crossing.targetSlice().rootPackage() + ", or move "
                                + ProjectClasses.nameOf(crossing.target()) + " there if other features need it"));
    }

    private static Stream<Violation> cycles(ProjectClasses classes, List<Crossing> crossings) {
        Map<String, Set<String>> edges = crossings.stream().collect(Collectors.groupingBy(
                crossing -> crossing.originSlice().name(),
                Collectors.mapping(crossing -> crossing.targetSlice().name(), Collectors.toSet())));
        Set<SortedSet<String>> components = edges.keySet().stream()
                .map(slice -> mutuallyReachable(edges, slice))
                .filter(component -> component.size() > 1)
                .collect(Collectors.toSet());
        return components.stream().map(component -> {
            Crossing example = crossings.stream()
                    .filter(crossing -> crossing.originSlice().name().equals(component.first())
                            && component.contains(crossing.targetSlice().name()))
                    .min(Comparator.comparingInt(Crossing::line))
                    .orElseThrow();
            return classes.violation(RULE, example.origin(), example.line(),
                    "slices " + joined(component) + " depend on each other in a cycle",
                    "move what they share into its own slice, or invert one dependency with an interface");
        });
    }

    private static SortedSet<String> mutuallyReachable(Map<String, Set<String>> edges, String start) {
        Set<String> forward = reachable(edges, start);
        return forward.stream()
                .filter(other -> reachable(edges, other).contains(start))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> reachable(Map<String, Set<String>> edges, String start) {
        Set<String> seen = new HashSet<>(Set.of(start));
        Deque<String> pending = new ArrayDeque<>(List.of(start));
        while (!pending.isEmpty()) {
            edges.getOrDefault(pending.pop(), Set.of()).stream().filter(seen::add).forEach(pending::push);
        }
        return seen;
    }

    private static String joined(Set<String> slices) {
        List<String> names = List.copyOf(slices);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static Optional<Crossing> crossing(List<String> prefixes, ClassDependency dependency) {
        Optional<Slice> origin = sliceOf(prefixes, dependency.origin());
        Optional<Slice> target = sliceOf(prefixes, dependency.target());
        if (origin.isEmpty() || target.isEmpty() || origin.get().equals(target.get())) {
            return Optional.empty();
        }
        return Optional.of(new Crossing(dependency.origin(), origin.get(), dependency.target(), target.get(),
                dependency.line()));
    }

    private static Optional<Slice> sliceOf(List<String> prefixes, JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        return prefixes.stream()
                .filter(prefix -> packageName.startsWith(prefix + "."))
                .findFirst()
                .map(prefix -> {
                    String name = packageName.substring(prefix.length() + 1).split("\\.")[0];
                    return new Slice(name, prefix + "." + name);
                });
    }

    /** A feature slice: its name and the package that is its API. */
    private record Slice(String name, String rootPackage) {
    }

    /** A dependency from one slice into another. */
    private record Crossing(JavaClass origin, Slice originSlice, JavaClass target, Slice targetSlice, int line) {
    }
}
