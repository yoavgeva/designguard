package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.DisabledTestsCheck;
import java.util.List;

/**
 * Fails on disabled tests: JUnit's {@code @Disabled} and {@code @Ignore} on a test or its class, and TestNG's
 * {@code @Test(enabled = false)}.
 */
public final class NoDisabledTestsRule extends DesignRule<NoDisabledTestsRule> {

    private final boolean allowWithReason;

    NoDisabledTestsRule(CodeBase code, ClassFilter filter, boolean allowWithReason) {
        super(code, filter);
        this.allowWithReason = allowWithReason;
    }

    /**
     * Allows a disabled test that states why, e.g. {@code @Disabled("JIRA-12: flaky until the clock is injected")}.
     *
     * @return the reconfigured rule
     */
    public NoDisabledTestsRule allowingWithReason() {
        return new NoDisabledTestsRule(code(), filter(), true);
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        return new DisabledTestsCheck(allowWithReason).check(code.testSources(), filter);
    }

    @Override
    NoDisabledTestsRule withFilter(ClassFilter filter) {
        return new NoDisabledTestsRule(code(), filter, allowWithReason);
    }
}
