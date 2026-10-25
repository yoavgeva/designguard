package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.internal.CodeBase;
import io.github.yoavgeva.designguard.model.AnnotationName;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Objects;

/**
 * A configured design rule. Rules are immutable: every configuration method returns a new rule.
 *
 * @param <T> the concrete rule type, so configuration methods keep returning it
 */
public abstract sealed class DesignRule<T extends DesignRule<T>> implements Checkable
        permits BasicRule, FeatureSlicesRule, HexagonalArchitectureRule, InheritanceRule, InterfacePolicyRule,
        LayeredArchitectureRule, MvcArchitectureRule, NoDisabledTestsRule, NoSuppressionsRule, OnionArchitectureRule,
        TypeDispatchRule {

    private final CodeBase codeBase;
    private final RuleScope ruleScope;

    DesignRule(CodeBase code, RuleScope scope) {
        this.codeBase = Objects.requireNonNull(code, "code");
        this.ruleScope = Objects.requireNonNull(scope, "scope");
    }

    /**
     * Starts choosing which classes the rule applies to, in ArchUnit's package syntax.
     *
     * @return a selector whose methods return the narrowed rule
     */
    public ClassSelector<T> that() {
        return new ClassSelector<>(filter -> withScope(ruleScope.withFilter(filter)), ruleScope.filter());
    }

    /**
     * Skips classes in packages matching any pattern, even if selected by {@link #that()}.
     *
     * @param patterns ArchUnit package patterns, e.g. {@code ..legacy..}
     * @return the narrowed rule
     */
    public T excluding(String... patterns) {
        return withScope(ruleScope.withFilter(ruleScope.filter().excluding(patterns)));
    }

    /**
     * Skips classes and members carrying the annotation.
     *
     * @param annotation see {@link AnnotationName#annotatedWith(Class)}
     * @return the narrowed rule
     */
    public T ignoring(AnnotationName annotation) {
        return withScope(ruleScope.withFilter(ruleScope.filter().ignoring(annotation)));
    }

    /**
     * Makes the rule warn instead of fail: its violations get {@link Violation.Severity#WARNING}, and
     * {@link #check()} logs them at {@code WARNING} through {@link System.Logger} and passes. Configuring the rule
     * further keeps it a warning.
     *
     * @return the rule, warning
     */
    public T asWarning() {
        return withScope(ruleScope.asWarning());
    }

    @Override
    public List<Violation> evaluate() {
        return violations(codeBase, ruleScope.filter()).stream()
                .map(violation -> violation.withSeverity(ruleScope.severity()))
                .toList();
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

    abstract T withScope(RuleScope scope);

    CodeBase code() {
        return codeBase;
    }

    RuleScope scope() {
        return ruleScope;
    }
}
