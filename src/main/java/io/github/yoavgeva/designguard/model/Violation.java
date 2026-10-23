package io.github.yoavgeva.designguard.model;

import java.util.Objects;

/**
 * One broken design rule, phrased so an agent can act on it: where, which rule, what happened, how to fix it.
 *
 * @param rule     stable rule id, e.g. {@code interface-policy}
 * @param severity whether the violation should block
 * @param file     path of the offending source file, relative to the project root
 * @param line     1-based line number, or 0 when unknown
 * @param message  what is wrong, naming the classes involved
 * @param fix      the concrete change that resolves it
 */
public record Violation(String rule, Severity severity, String file, int line, String message, String fix) {

    /** Whether a violation blocks the change or only warns about it. */
    public enum Severity {
        /** Blocks the change. */
        ERROR,
        /** Reported, but does not block. */
        WARNING
    }

    /**
     * Validates the components.
     *
     * @throws NullPointerException     if any component except {@code line} is null
     * @throws IllegalArgumentException if {@code line} is negative
     */
    public Violation {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(fix, "fix");
        if (line < 0) {
            throw new IllegalArgumentException("line must be >= 0, was " + line);
        }
    }
}
