package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.model.FreezeResult;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.report.TextReport;
import java.util.List;

/**
 * What a check does with what it found: errors fail it with an {@link AssertionError}, and warnings are logged at
 * {@code WARNING} through {@link System.Logger}, so they reach the project's logging backend, or standard error
 * without one.
 */
final class Verdicts {

    private static final System.Logger LOG = System.getLogger("io.github.yoavgeva.designguard");

    private Verdicts() {
    }

    /**
     * Logs the warnings among the violations, then fails if any is an error.
     *
     * @param violations what a rule or rule set found
     * @throws AssertionError listing the errors, if there are any
     */
    static void deliver(List<Violation> violations) {
        TextReport report = new TextReport();
        List<Violation> warnings = violations.stream().filter(Verdicts::isWarning).toList();
        List<Violation> errors = violations.stream().filter(violation -> !isWarning(violation)).toList();
        if (!warnings.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, report.renderWarnings(warnings));
        }
        if (!errors.isEmpty()) {
            throw new AssertionError(report.renderFailure(errors));
        }
    }

    /**
     * Fails if a violation that fails a frozen rule is an error, and logs the report if only warnings fail it.
     *
     * @param result what the frozen rule found
     * @throws AssertionError with the report, if an error fails the rule
     */
    static void deliver(FreezeResult result) {
        if (result.failing().isEmpty()) {
            return;
        }
        String report = new TextReport().renderFrozen(result);
        if (result.failing().stream().allMatch(Verdicts::isWarning)) {
            LOG.log(System.Logger.Level.WARNING, report);
        } else {
            throw new AssertionError(report);
        }
    }

    private static boolean isWarning(Violation violation) {
        return violation.severity() == Violation.Severity.WARNING;
    }
}
