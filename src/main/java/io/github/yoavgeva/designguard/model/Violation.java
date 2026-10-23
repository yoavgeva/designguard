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
 * @param identity what the violation is about, without details that change as unrelated code does, such as counts;
 *                 frozen rules match violations by it. Usually the message.
 */
public record Violation(String rule, Severity severity, String file, int line, String message, String fix,
        String identity) {

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
        Objects.requireNonNull(identity, "identity");
        if (line < 0) {
            throw new IllegalArgumentException("line must be >= 0, was " + line);
        }
    }

    /**
     * A violation identified by its message.
     *
     * @param rule     stable rule id, e.g. {@code interface-policy}
     * @param severity whether the violation should block
     * @param file     path of the offending source file, relative to the project root
     * @param line     1-based line number, or 0 when unknown
     * @param message  what is wrong, naming the classes involved
     * @param fix      the concrete change that resolves it
     * @throws NullPointerException     if any component except {@code line} is null
     * @throws IllegalArgumentException if {@code line} is negative
     */
    public Violation(String rule, Severity severity, String file, int line, String message, String fix) {
        this(rule, severity, file, line, message, fix, message);
    }

    /**
     * The same violation with another severity.
     *
     * @param changed the severity
     * @return this violation if it already has the severity, else a copy with it
     */
    public Violation withSeverity(Severity changed) {
        return changed == severity ? this : new Violation(rule, changed, file, line, message, fix, identity);
    }

    /**
     * The same violation, identified by what it is about rather than its message.
     *
     * @param about what it is about, without counts or other details that move as unrelated code changes
     * @return the violation with that identity
     */
    public Violation identifiedAs(String about) {
        return new Violation(rule, severity, file, line, message, fix, about);
    }
}
