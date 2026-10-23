package io.github.yoavgeva.designguard.model;

/** When a dependency must go through an interface instead of a concrete class. */
public enum InterfacePolicy {
    /** Only when a service-like class is used across a boundary between slices (onion and hexagonal ports). */
    PORTS,
    /** Whenever a service-like class is used, even inside one slice. */
    SERVICES,
    /** Whenever any project class is used, values included. Exceptions and annotations are exempt. */
    ALL
}
