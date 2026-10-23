package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;

/**
 * Which classes a rule checks, and whether its violations fail or only warn.
 *
 * @param filter   the classes the rule checks
 * @param severity the severity its violations get
 */
record RuleScope(ClassFilter filter, Violation.Severity severity) {

    /** Every class, failing on violations. */
    static final RuleScope DEFAULT = new RuleScope(ClassFilter.ALL, Violation.Severity.ERROR);

    RuleScope withFilter(ClassFilter changed) {
        return new RuleScope(changed, severity);
    }

    RuleScope asWarning() {
        return new RuleScope(filter, Violation.Severity.WARNING);
    }
}
