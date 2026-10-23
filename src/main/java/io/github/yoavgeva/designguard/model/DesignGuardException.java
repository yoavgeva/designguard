package io.github.yoavgeva.designguard.model;

import java.io.Serial;

/** Thrown when designguard cannot read the code it should check, e.g. a missing source root or unparsable source. */
public final class DesignGuardException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed and how to fix it
     */
    public DesignGuardException(String message) {
        super(message);
    }

    /**
     * Creates the exception with its cause.
     *
     * @param message what failed and how to fix it
     * @param cause   the underlying failure
     */
    public DesignGuardException(String message, Throwable cause) {
        super(message, cause);
    }
}
