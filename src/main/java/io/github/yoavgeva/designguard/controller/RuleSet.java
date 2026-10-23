package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.AnnotationName;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.CodeBase;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Several rules run as one: one check, one report ordered by file and line, one frozen record. A selection made with
 * {@link #that()}, {@link #excluding(String...)} or {@link #ignoring(AnnotationName)} applies to every rule, and
 * {@link #warningOn(String...)} turns some of them into warnings. Rule sets are immutable: every configuration method
 * returns a new set. Get one from {@link DesignGuard#recommended()}.
 */
public final class RuleSet implements Checkable {

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
        Set<String> left = held(ids);
        return new RuleSet(code, members.stream().filter(member -> !left.contains(member.id())).toList(), classFilter);
    }

    /**
     * Makes some rules warn instead of fail: {@link #check()} logs their violations at {@code WARNING} through
     * {@link System.Logger}, and fails only on the others'.
     *
     * @param ids the ids of the rules that warn, as violations show them, e.g. {@code no-suppressions}
     * @return the set, with those rules warning
     * @throws IllegalArgumentException if the set has no rule with one of the ids
     */
    public RuleSet warningOn(String... ids) {
        Set<String> warning = held(ids);
        return new RuleSet(code, members.stream()
                .map(member -> warning.contains(member.id()) ? member.asWarning() : member)
                .toList(), classFilter);
    }

    /**
     * Makes every rule warn instead of fail: {@link #check()} logs the violations and passes.
     *
     * @return the set, warning
     */
    public RuleSet asWarning() {
        return warningOn(rules().toArray(String[]::new));
    }

    /**
     * Starts choosing which classes every rule applies to, in ArchUnit's package syntax.
     *
     * @return a selector whose methods return the narrowed set
     */
    public ClassSelector<RuleSet> that() {
        return new ClassSelector<>(filter -> new RuleSet(code, members, filter), classFilter);
    }

    /**
     * Skips classes in packages matching any pattern, in every rule.
     *
     * @param patterns ArchUnit package patterns, e.g. {@code ..legacy..}
     * @return the narrowed set
     */
    public RuleSet excluding(String... patterns) {
        return new RuleSet(code, members, classFilter.excluding(patterns));
    }

    /**
     * Skips classes and members carrying the annotation, in every rule.
     *
     * @param annotation see {@link AnnotationName#annotatedWith(Class)}
     * @return the narrowed set
     */
    public RuleSet ignoring(AnnotationName annotation) {
        return new RuleSet(code, members, classFilter.ignoring(annotation));
    }

    @Override
    public List<Violation> evaluate() {
        return members.stream()
                .flatMap(member -> member.rule().withScope(new RuleScope(classFilter, member.severity()))
                        .evaluate().stream())
                .sorted(BY_LOCATION)
                .toList();
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

    /** The ids, as a set, failing on one the set has no rule for. */
    private Set<String> held(String... ids) {
        Set<String> wanted = Set.of(ids);
        List<String> known = rules();
        wanted.stream().filter(id -> !known.contains(id)).sorted().findFirst().ifPresent(id -> {
            throw new IllegalArgumentException("the rule set has no rule " + id + "; its rules are "
                    + String.join(", ", known));
        });
        return wanted;
    }

    /**
     * A rule in a set.
     *
     * @param id       the id its violations carry
     * @param rule     the rule
     * @param severity whether its violations fail the set or warn
     */
    record Member(String id, DesignRule<?> rule, Violation.Severity severity) {

        Member(String id, DesignRule<?> rule) {
            this(id, rule, Violation.Severity.ERROR);
        }

        Member asWarning() {
            return new Member(id, rule, Violation.Severity.WARNING);
        }
    }
}
