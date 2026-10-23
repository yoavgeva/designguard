package io.github.yoavgeva.designguard.model;

import java.util.List;
import java.util.Objects;

/**
 * What running a frozen rule found, compared with the violations it records.
 *
 * @param record  the file that records the frozen violations
 * @param outcome whether this run recorded the violations or compared them with the record
 * @param frozen  how many violations the record holds after this run
 * @param failing the violations that fail the rule: every violation when they were just recorded, else those the
 *                record doesn't hold
 */
public record FreezeResult(String record, Outcome outcome, int frozen, List<Violation> failing) {

    /** Whether a run created the record or checked against it. */
    public enum Outcome {
        /** No record existed, so this run recorded every violation. */
        RECORDED,
        /** A record existed, so this run compared the violations with it and dropped the fixed ones. */
        COMPARED
    }

    /**
     * Copies the violations.
     *
     * @throws NullPointerException if any component is null
     */
    public FreezeResult {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(outcome, "outcome");
        failing = List.copyOf(failing);
    }
}
