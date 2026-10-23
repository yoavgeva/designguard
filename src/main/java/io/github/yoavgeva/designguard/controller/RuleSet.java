package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.AnnotationName;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.view.TextReport;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Several rules run as one: one check, one report ordered by file and line, one frozen record. A selection made with
 * {@link #that()}, {@link #excluding(String...)} or {@link #ignoring(AnnotationName)} applies to every rule. Rule
 * sets are immutable: every configuration method returns a new set. Get one from {@link DesignGuard#recommended()}.
 */
public final class RuleSet {

    private static final Comparator<Violation> BY_LOCATION =
            Comparator.comparing(Violation::file).thenComparingInt(Violation::line);

    private final CodeBase code;
    private final List<Member> members;
    private final ClassFilter classFilter;

    RuleSet(CodeBase code, List<Member> members, ClassFilter filter) {
        this.code = Objects.requireNonNull(code, "code");
        this.members = List.copyOf(members);
        this.classFilter = Objects.requireNonNull(filter, "filter");
    }

    /**
     * The rules in the set, by the id their violations carry, e.g. {@code no-stubs}.
     *
     * @return the ids, in the set's order
     */
    public List<String> rules() {
        return members.stream().map(Member::id).toList();
    }

    /**
     * Leaves rules out of the set.
     *
     * @param ids the ids of the rules to leave out, as violations show them, e.g. {@code no-suppressions}
     * @return the smaller set
     * @throws IllegalArgumentException if the set has no rule with one of the ids
     */
    public RuleSet without(String... ids) {
        Set<String> left = Set.of(ids);
        left.stream().filter(id -> !rules().contains(id)).sorted().findFirst().ifPresent(id -> {
            throw new IllegalArgumentException("the rule set has no rule " + id + "; its rules are "
                    + String.join(", ", rules()));
        });
        return new RuleSet(code, members.stream().filter(member -> !left.contains(member.id())).toList(), classFilter);
    }

    /**
     * Starts choosing which classes every rule applies to, in ArchUnit's package syntax.
     *
     * @return a selector whose methods return the narrowed set
     */
    public ClassSelector<RuleSet> that() {
        return new ClassSelector<>(this::withFilter, classFilter);
    }

    /**
     * Skips classes in packages matching any pattern, in every rule.
     *
     * @param patterns ArchUnit package patterns, e.g. {@code ..legacy..}
     * @return the narrowed set
     */
    public RuleSet excluding(String... patterns) {
        return withFilter(classFilter.excluding(patterns));
    }

    /**
     * Skips classes and members carrying the annotation, in every rule.
     *
     * @param annotation see {@link AnnotationName#annotatedWith(Class)}
     * @return the narrowed set
     */
    public RuleSet ignoring(AnnotationName annotation) {
        return withFilter(classFilter.ignoring(annotation));
    }

    /**
     * Runs every rule.
     *
     * @return every violation, ordered by file and line
     */
    public List<Violation> evaluate() {
        return members.stream()
                .flatMap(member -> member.rule().withFilter(classFilter).evaluate().stream())
                .sorted(BY_LOCATION)
                .toList();
    }

    /**
     * Runs every rule and fails if anything violates one.
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
     * Adopts every rule on code that already breaks them, in one record: see {@link DesignRule#frozen(String)}.
     *
     * @param name the record's name, unique among the project's frozen rules, e.g. {@code recommended}
     * @return the frozen set
     * @throws IllegalArgumentException if the name isn't a plain file name
     */
    public FrozenRule frozen(String name) {
        return new FrozenRule(this::evaluate, code.frozenViolations(name));
    }

    private RuleSet withFilter(ClassFilter filter) {
        return new RuleSet(code, members, filter);
    }

    /**
     * A rule in a set.
     *
     * @param id   the id its violations carry
     * @param rule the rule
     */
    record Member(String id, DesignRule<?> rule) {
    }
}
