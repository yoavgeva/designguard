package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.internal.CodeBase;
import io.github.yoavgeva.designguard.internal.DisabledTestsCheck;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;

/**
 * Fails on disabled tests: JUnit's {@code @Disabled} and {@code @Ignore} on a test or its class, and TestNG's
 * {@code @Test(enabled = false)}.
 */
public final class NoDisabledTestsRule extends DesignRule<NoDisabledTestsRule> {

    private final boolean allowWithReason;

    NoDisabledTestsRule(CodeBase code, RuleScope scope, boolean allowWithReason) {
        super(code, scope);
        this.allowWithReason = allowWithReason;
    }

    /**
     * Allows a disabled test that states why, e.g. {@code @Disabled("JIRA-12: flaky until the clock is injected")}.
     *
     * @return the reconfigured rule
     */
    public NoDisabledTestsRule allowingWithReason() {
        return new NoDisabledTestsRule(code(), scope(), true);
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        return new DisabledTestsCheck(allowWithReason).check(code.testSources(), filter);
    }

    @Override
    NoDisabledTestsRule withScope(RuleScope scope) {
        return new NoDisabledTestsRule(code(), scope, allowWithReason);
    }
}
