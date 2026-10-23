package io.github.yoavgeva.designguard.view;

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
