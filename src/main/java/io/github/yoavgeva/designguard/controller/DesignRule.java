package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.AnnotationName;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.view.TextReport;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;

/**
 * A configured design rule. Rules are immutable: every configuration method returns a new rule.
 *
 * @param <T> the concrete rule type, so configuration methods keep returning it
 */
public abstract class DesignRule<T extends DesignRule<T>> {

    private final CodeBase codeBase;
    private final ClassFilter classFilter;

    DesignRule(CodeBase code, ClassFilter filter) {
        this.codeBase = Objects.requireNonNull(code, "code");
        this.classFilter = Objects.requireNonNull(filter, "filter");
    }

    /**
     * Names an annotation for {@link #ignoring(AnnotationName)}.
     *
     * @param annotation the annotation type
     * @return its name, matched simply or qualified against the code
     */
    public static AnnotationName annotatedWith(Class<? extends Annotation> annotation) {
        return new AnnotationName(annotation.getSimpleName(), annotation.getCanonicalName());
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
     * @param annotation see {@link #annotatedWith(Class)}
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
            String header = violations.size() == 1 ? "1 design violation:\n" : violations.size() + " design violations:\n";
            throw new AssertionError(header + new TextReport().render(violations));
        }
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
