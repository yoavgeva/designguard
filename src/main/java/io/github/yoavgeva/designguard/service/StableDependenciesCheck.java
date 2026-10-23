package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Stable Dependencies Principle: depend in the direction of stability. Each slice's instability is
 * {@code I = Ce / (Ca + Ce)}, where Ce counts its classes that depend on other slices and Ca counts other slices'
 * classes that depend on it. A slice depending on a slice with a higher I is reported.
 */
public final class StableDependenciesCheck {

    private static final String RULE = "stable-dependencies";

    /**
     * Finds dependencies on less stable slices from the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        Slices slices = Slices.belowEach(code.settings().packages());
        List<Crossing> crossings = classes.stream()
                .flatMap(classes.bytecode()::dependenciesFrom)
                .filter(dependency -> classes.isProject(dependency.target()))
                .map(dependency -> new Crossing(dependency.origin(),
                        slices.sliceOf(dependency.origin().getPackageName()),
                        slices.sliceOf(dependency.target().getPackageName()),
                        dependency.line()))
                .filter(crossing -> !crossing.from().isEmpty() && !crossing.to().isEmpty()
                        && !crossing.from().equals(crossing.to()))
                .toList();
        Map<String, Double> instability = instability(crossings);
        return crossings.stream()
                .filter(crossing -> instability.get(crossing.to()) > instability.get(crossing.from()))
                .filter(crossing -> classes.selected(filter, crossing.origin()))
                .collect(Collectors.groupingBy(crossing -> crossing.from() + ">" + crossing.to(),
                        Collectors.minBy(Comparator.comparingInt(Crossing::line))))
                .values().stream()
                .flatMap(Optional::stream)
                .map(crossing -> classes.violation(RULE, crossing.origin(), crossing.line(),
                        "slice " + crossing.from() + " (instability " + format(instability.get(crossing.from()))
                                + ") depends on slice " + crossing.to() + ", which is less stable (instability "
                                + format(instability.get(crossing.to())) + ")",
                        "invert it: let " + crossing.from() + " own an interface that " + crossing.to()
                                + " implements, or move what " + crossing.from() + " needs into " + crossing.from())
                        .identifiedAs("slice " + crossing.from() + " depends on less stable slice " + crossing.to()))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Map<String, Double> instability(List<Crossing> crossings) {
        Map<String, Set<String>> outgoing = classesBySlice(crossings, Crossing::from);
        Map<String, Set<String>> incoming = classesBySlice(crossings, Crossing::to);
        return crossings.stream()
                .flatMap(crossing -> Stream.of(crossing.from(), crossing.to()))
                .distinct()
                .collect(Collectors.toMap(slice -> slice, slice -> {
                    double efferent = outgoing.getOrDefault(slice, Set.of()).size();
                    double afferent = incoming.getOrDefault(slice, Set.of()).size();
                    return efferent + afferent == 0 ? 0 : efferent / (efferent + afferent);
                }));
    }

    private static Map<String, Set<String>> classesBySlice(List<Crossing> crossings, Function<Crossing, String> slice) {
        return crossings.stream().collect(Collectors.groupingBy(slice,
                Collectors.mapping(crossing -> crossing.origin().getName(), Collectors.toSet())));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** A dependency from a class in one slice to a class in another. */
    private record Crossing(JavaClass origin, String from, String to, int line) {
    }
}
