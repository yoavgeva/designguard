package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;

/** What a test runs against the code: a {@link DesignRule}, a {@link RuleSet} or a {@link FrozenRule}. */
public sealed interface Checkable permits DesignRule, RuleSet, FrozenRule {

    /**
     * Runs the check.
     *
     * @return every violation found, ordered by file and line
     */
    List<Violation> evaluate();

    /**
     * Runs the check and fails if anything violates it. Violations of rules that {@link DesignRule#asWarning() warn}
     * are logged at {@code WARNING} through {@link System.Logger} instead.
     *
     * @throws AssertionError listing every failing violation, one actionable line each
     */
    default void check() {
        Verdicts.deliver(evaluate());
    }
}
