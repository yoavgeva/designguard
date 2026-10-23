package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import java.util.function.Function;

/**
 * Chooses the classes a rule applies to, using ArchUnit's package syntax ({@code ..billing..}, {@code com.shop..}).
 *
 * @param <T> the rule type returned once the selection is made
 */
public final class ClassSelector<T> {

    private final Function<ClassFilter, T> apply;
    private final ClassFilter filter;

    ClassSelector(Function<ClassFilter, T> apply, ClassFilter filter) {
        this.apply = apply;
        this.filter = filter;
    }

    /**
     * Selects classes in packages matching the pattern.
     *
     * @param pattern an ArchUnit package pattern
     * @return the narrowed rule
     */
    public T resideInAPackage(String pattern) {
        return apply.apply(filter.including(pattern));
    }

    /**
     * Selects classes in packages matching any of the patterns.
     *
     * @param patterns ArchUnit package patterns
     * @return the narrowed rule
     */
    public T resideInAnyPackage(String... patterns) {
        return apply.apply(filter.including(patterns));
    }

    /**
     * Selects classes outside packages matching the pattern.
     *
     * @param pattern an ArchUnit package pattern
     * @return the narrowed rule
     */
    public T resideOutsideOfPackage(String pattern) {
        return apply.apply(filter.excluding(pattern));
    }

    /**
     * Selects classes outside packages matching any of the patterns.
     *
     * @param patterns ArchUnit package patterns
     * @return the narrowed rule
     */
    public T resideOutsideOfPackages(String... patterns) {
        return apply.apply(filter.excluding(patterns));
    }
}
