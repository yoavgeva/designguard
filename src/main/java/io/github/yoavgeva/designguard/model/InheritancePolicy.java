package io.github.yoavgeva.designguard.model;

/** How much a project may use class inheritance. Composition over inheritance (Effective Java, item 18). */
public enum InheritancePolicy {
    /** Concrete classes nothing extends must be final: design for inheritance or prohibit it. */
    FINAL_BY_DEFAULT,
    /** Classes may extend abstract classes only, never a concrete class. */
    ABSTRACT_ONLY,
    /** Any inheritance; only the depth limit applies. */
    ANY
}
