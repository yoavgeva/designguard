package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ProjectSettings;
import io.github.yoavgeva.designguard.service.CodeBase;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point: describes the code to check and hands out design rules for it, grouped by topic. Use it from a plain
 * JUnit test:
 *
 * <pre>{@code
 * private static final DesignGuard GUARD = new DesignGuard("com.shop");
 *
 * @Test
 * void dependOnPorts() {
 *     GUARD.dependencies().interfacePolicy(InterfacePolicy.PORTS).excluding("..legacy..").check();
 * }
 * }</pre>
 *
 * <p>Sources are read from {@code src/main/java} and {@code src/test/java} relative to the working directory, which
 * is the module directory under Maven and Gradle; compiled classes come from the classpath.
 */
public final class DesignGuard {

    private final ProjectSettings settings;
    private final CodeBase code;

    /**
     * Checks the given packages and their subpackages.
     *
     * @param packages package names, e.g. {@code com.shop}
     * @throws IllegalArgumentException if no package is given
     */
    public DesignGuard(String... packages) {
        this(ProjectSettings.forPackages(List.of(packages)));
    }

    private DesignGuard(ProjectSettings settings) {
        this.settings = settings;
        this.code = CodeBase.of(settings);
    }

    /**
     * Reads production sources from other roots than {@code src/main/java}.
     *
     * @param roots source roots
     * @return a new guard
     */
    public DesignGuard withSourceRoots(Path... roots) {
        return new DesignGuard(settings.withSourceRoots(List.of(roots)));
    }

    /**
     * Reads test sources from other roots than {@code src/test/java}. Roots that don't exist are skipped.
     *
     * @param roots test source roots
     * @return a new guard
     */
    public DesignGuard withTestSourceRoots(Path... roots) {
        return new DesignGuard(settings.withTestSourceRoots(List.of(roots)));
    }

    /**
     * Reads compiled classes from directories instead of the classpath.
     *
     * @param directories class directories, e.g. {@code target/classes}
     * @return a new guard
     */
    public DesignGuard withClassesFrom(Path... directories) {
        return new DesignGuard(settings.withClassDirectories(List.of(directories)));
    }

    /**
     * Marks packages as wiring code, which may create and hold concrete services. Classes with a {@code main}
     * method and {@code @Configuration}, {@code @SpringBootApplication} or {@code @Module} classes always are.
     *
     * @param patterns ArchUnit package patterns, e.g. {@code ..config..}
     * @return a new guard
     */
    public DesignGuard wiringIn(String... patterns) {
        return new DesignGuard(settings.withWiringPackages(List.of(patterns)));
    }

    /**
     * Treats using these packages as impure, like I/O: classes using them become service-like. Use it for SDK
     * clients, e.g. {@code com.stripe..} or {@code software.amazon.awssdk..}.
     *
     * @param patterns ArchUnit package patterns
     * @return a new guard
     */
    public DesignGuard treatingAsImpure(String... patterns) {
        return new DesignGuard(settings.withImpurePackages(List.of(patterns)));
    }

    /**
     * Sets the Java version the checked code targets, instead of reading it from the class files. Rules that need a
     * newer Java fail with an explanation, and fixes only suggest features that version has.
     *
     * @param release the Java version, e.g. 17
     * @return a new guard
     * @throws IllegalArgumentException if the version is below 8
     */
    public DesignGuard forJava(int release) {
        return new DesignGuard(settings.withJavaRelease(release));
    }

    /**
     * Architecture presets: layered, MVC, hexagonal, onion, package by feature.
     *
     * @return the architecture rules
     */
    public ArchitectureRules architecture() {
        return new ArchitectureRules(code);
    }

    /**
     * Rules about how classes depend on each other: interfaces, injection, stability, boundaries.
     *
     * @return the dependency rules
     */
    public DependencyRules dependencies() {
        return new DependencyRules(code);
    }

    /**
     * Rules about how types are modelled: hierarchies, records, immutability, branching on kinds.
     *
     * @return the type rules
     */
    public TypeRules types() {
        return new TypeRules(code);
    }

    /**
     * Rules about methods and types other code calls: parameters, nullness, exceptions, visibility.
     *
     * @return the API rules
     */
    public ApiRules api() {
        return new ApiRules(code);
    }

    /**
     * Rules that catch AI shortcuts: stubs, suppressions, weak tests.
     *
     * @return the guardrail rules
     */
    public GuardrailRules guardrails() {
        return new GuardrailRules(code);
    }
}
