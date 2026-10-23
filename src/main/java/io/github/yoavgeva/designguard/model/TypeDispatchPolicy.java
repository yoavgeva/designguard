package io.github.yoavgeva.designguard.model;

/** Whether code may branch on the concrete subtype of a project type. */
public enum TypeDispatchPolicy {
    /**
     * Only over sealed types, through a switch the compiler checks for missing cases. Data-oriented Java style:
     * records plus sealed interfaces plus pattern matching.
     */
    SEALED_ONLY,
    /** Over any type. The per-type place limit and the default-branch check still apply. */
    ALLOW
}
