package io.github.yoavgeva.designguard.model;

import java.util.List;
import java.util.Objects;

/**
 * A named group of packages in an architecture, such as a layer, a ring or an adapter.
 *
 * @param name     shown in violations, e.g. {@code Persistence}
 * @param packages ArchUnit package patterns of the classes in it, e.g. {@code ..persistence..}
 */
public record Layer(String name, List<String> packages) {

    /**
     * Copies the packages.
     *
     * @throws NullPointerException if any component is null
     */
    public Layer {
        Objects.requireNonNull(name, "name");
        packages = List.copyOf(packages);
    }
}
