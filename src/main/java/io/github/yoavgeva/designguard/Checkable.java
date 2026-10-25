package io.github.yoavgeva.designguard;

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

    /**
     * Checks violations found earlier, as {@link #check()} would have: logs the warnings and fails on the errors. Use
     * it to write a report and fail with one run of the rules:
     *
     * <pre>{@code
     * List<Violation> found = GUARD.recommended().evaluate();
     * Files.writeString(Path.of("target/designguard.sarif"), new SarifReport().render(found));
     * Checkable.check(found);
     * }</pre>
     *
     * @param found the violations, in order
     * @throws AssertionError listing every error, one actionable line each
     */
    static void check(List<Violation> found) {
        Verdicts.deliver(found);
    }
}
