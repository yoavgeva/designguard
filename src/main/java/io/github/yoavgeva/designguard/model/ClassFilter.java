package io.github.yoavgeva.designguard.model;

import com.tngtech.archunit.core.domain.PackageMatcher;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * Which classes a rule applies to. Package patterns use ArchUnit's syntax, e.g. {@code ..billing..}.
 *
 * @param includedPackages   if not empty, only classes in a matching package are selected
 * @param excludedPackages   classes in a matching package are never selected, even if included
 * @param ignoredAnnotations classes or members carrying any of these are never selected
 */
public record ClassFilter(
        List<String> includedPackages, List<String> excludedPackages, List<AnnotationName> ignoredAnnotations) {

    /** Selects every class. */
    public static final ClassFilter ALL = new ClassFilter(List.of(), List.of(), List.of());

    /**
     * Copies all lists.
     *
     * @throws NullPointerException if any list or element is null
     */
    public ClassFilter {
        includedPackages = List.copyOf(includedPackages);
        excludedPackages = List.copyOf(excludedPackages);
        ignoredAnnotations = List.copyOf(ignoredAnnotations);
    }

    /**
     * Adds package patterns to select. Classes matching any included pattern are selected.
     *
     * @param patterns ArchUnit package patterns
     * @return a new filter
     */
    public ClassFilter including(String... patterns) {
        return new ClassFilter(concat(includedPackages, List.of(patterns)), excludedPackages, ignoredAnnotations);
    }

    /**
     * Adds package patterns to skip.
     *
     * @param patterns ArchUnit package patterns
     * @return a new filter
     */
    public ClassFilter excluding(String... patterns) {
        return new ClassFilter(includedPackages, concat(excludedPackages, List.of(patterns)), ignoredAnnotations);
    }

    /**
     * Adds an annotation whose carriers are skipped.
     *
     * @param annotation the annotation to ignore
     * @return a new filter
     */
    public ClassFilter ignoring(AnnotationName annotation) {
        return new ClassFilter(includedPackages, excludedPackages, concat(ignoredAnnotations, List.of(annotation)));
    }

    /**
     * Whether a class, or a member of it, is selected.
     *
     * @param packageName     the class's package, empty for the default package
     * @param annotationNames annotations on the class or member, as written in source or as qualified names
     * @return true if the rule applies to it
     */
    public boolean selects(String packageName, Collection<String> annotationNames) {
        boolean included = includedPackages.isEmpty() || matchesAny(includedPackages, packageName);
        boolean ignored = annotationNames.stream()
                .anyMatch(name -> ignoredAnnotations.stream().anyMatch(annotation -> annotation.matches(name)));
        return included && !matchesAny(excludedPackages, packageName) && !ignored;
    }

    private static boolean matchesAny(List<String> patterns, String packageName) {
        return patterns.stream().anyMatch(pattern -> PackageMatcher.of(pattern).matches(packageName));
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }
}
