package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.FreezeResult;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.ViolationStore;
import io.github.yoavgeva.designguard.view.TextReport;
import java.util.List;
import java.util.function.Supplier;

/**
 * A rule adopted on code that already breaks it: the violations it had when frozen are recorded in a file, and only
 * new ones fail. Fixed violations drop out of the file, so it only shrinks. Create one with
 * {@link DesignRule#frozen(String)}.
 */
public final class FrozenRule {

    private final Supplier<List<Violation>> rule;
    private final ViolationStore store;

    FrozenRule(Supplier<List<Violation>> rule, ViolationStore store) {
        this.rule = rule;
        this.store = store;
    }

    /**
     * Runs the rule and updates the record: creates it on the first run, and drops fixed violations later.
     *
     * @return the violations that fail: on the first run every violation, as they were just recorded; later, those
     *         the record doesn't list
     */
    public List<Violation> evaluate() {
        return store.freeze(rule.get()).failing();
    }

    /**
     * Runs the rule, updates the record, and fails if any violation fails.
     *
     * @throws AssertionError on the first run if there are violations, so the new record gets committed; later, if a
     *                        violation isn't recorded
     */
    public void check() {
        FreezeResult result = store.freeze(rule.get());
        if (!result.failing().isEmpty()) {
            throw new AssertionError(new TextReport().renderFrozen(result));
        }
    }
}
