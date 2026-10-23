package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.AnnotationName;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.view.TextReport;
import java.util.List;
import java.util.Objects;

/**
 * A configured design rule. Rules are immutable: every configuration method returns a new rule.
 *
 * @param <T> the concrete rule type, so configuration methods keep returning it
 */
public abstract sealed class DesignRule<T extends DesignRule<T>>
        permits BasicRule, FeatureSlicesRule, HexagonalArchitectureRule, InheritanceRule, InterfacePolicyRule,
        LayeredArchitectureRule, MvcArchitectureRule, NoDisabledTestsRule, NoSuppressionsRule, OnionArchitectureRule,
        TypeDispatchRule {

    private final CodeBase codeBase;
    private final ClassFilter classFilter;

    DesignRule(CodeBase code, ClassFilter filter) {
        this.codeBase = Objects.requireNonNull(code, "code");
        this.classFilter = Objects.requireNonNull(filter, "filter");
    }

    /**
     * Starts choosing which classes the rule applies to, in ArchUnit's package syntax.
     *
     * @return a selector whose methods return the narrowed rule
     */
    public ClassSelector<T> that() {
        return new ClassSelector<>(this::withFilter, classFilter);
    }

    /**
     * Skips classes in packages matching any pattern, even if selected by {@link #that()}.
     *
     * @param patterns ArchUnit package patterns, e.g. {@code ..legacy..}
     * @return the narrowed rule
     */
    public T excluding(String... patterns) {
        return withFilter(classFilter.excluding(patterns));
    }

    /**
     * Skips classes and members carrying the annotation.
     *
     * @param annotation see {@link AnnotationName#annotatedWith(Class)}
     * @return the narrowed rule
     */
    public T ignoring(AnnotationName annotation) {
        return withFilter(classFilter.ignoring(annotation));
    }

    /**
     * Runs the rule.
     *
     * @return every violation, ordered by file and line
     */
    public List<Violation> evaluate() {
        return violations(codeBase, classFilter);
    }

    /**
     * Runs the rule and fails if anything violates it.
     *
     * @throws AssertionError listing every violation, one actionable line each
     */
    public void check() {
        List<Violation> violations = evaluate();
        if (!violations.isEmpty()) {
            throw new AssertionError(new TextReport().renderFailure(violations));
        }
    }

    /**
     * Adopts the rule on code that already breaks it: the first run records the violations in
     * {@code src/test/designguard/frozen/<name>.txt} and fails once, so the file gets committed. Later runs fail only
     * on violations the file doesn't list, and drop the fixed ones from it.
     *
     * @param name the file's name, unique among the project's frozen rules, e.g. {@code layers}
     * @return the frozen rule
     * @throws IllegalArgumentException if the name isn't a plain file name
     */
    public FrozenRule frozen(String name) {
        return new FrozenRule(this::evaluate, codeBase.frozenViolations(name));
    }

    abstract List<Violation> violations(CodeBase code, ClassFilter filter);

    abstract T withFilter(ClassFilter filter);

    CodeBase code() {
        return codeBase;
    }

    ClassFilter filter() {
        return classFilter;
    }
}
