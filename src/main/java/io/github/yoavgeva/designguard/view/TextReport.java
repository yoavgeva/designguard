package io.github.yoavgeva.designguard.view;

import io.github.yoavgeva.designguard.model.FreezeResult;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Renders violations compiler-style, one actionable line each, so agents and terminals can both parse them. */
public final class TextReport {

    /**
     * Renders all violations, one per line.
     *
     * @param violations the violations to render, in order
     * @return the report, lines separated by {@code \n}
     */
    public String render(List<Violation> violations) {
        return violations.stream().map(this::line).collect(Collectors.joining("\n"));
    }

    /**
     * Renders why a check failed: how many violations there are, then one line each.
     *
     * @param violations the violations, in order; at least one
     * @return the report, lines separated by {@code \n}
     */
    public String renderFailure(List<Violation> violations) {
        String header = violations.size() == 1 ? "1 design violation:\n" : violations.size() + " design violations:\n";
        return header + render(violations);
    }

    /**
     * Renders what a frozen rule found: a header saying whether the violations were just recorded or are new, then
     * one line per failing violation.
     *
     * @param result the frozen rule's result
     * @return the report, lines separated by {@code \n}
     */
    public String renderFrozen(FreezeResult result) {
        List<Violation> failing = result.failing();
        String violations = failing.size() == 1 ? " design violation" : " design violations";
        String header = result.outcome() == FreezeResult.Outcome.RECORDED
                ? "Recorded " + failing.size() + violations + " in " + result.record() + ". Commit the file: from now"
                        + " on, only new violations fail.\n"
                : failing.size() + " new" + violations + " (" + result.frozen() + " frozen in " + result.record() + "):\n";
        return header + render(failing);
    }

    /**
     * Renders one violation as {@code file:line: severity [rule] message -> fix: hint}.
     *
     * @param violation the violation to render
     * @return the line, without a trailing newline
     */
    public String line(Violation violation) {
        String location = violation.line() > 0 ? violation.file() + ":" + violation.line() : violation.file();
        return location + ": " + violation.severity().name().toLowerCase(Locale.ROOT)
                + " [" + violation.rule() + "] " + violation.message() + " -> fix: " + violation.fix();
    }
}
