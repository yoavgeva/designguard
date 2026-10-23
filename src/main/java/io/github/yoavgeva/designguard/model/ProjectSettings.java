package io.github.yoavgeva.designguard.model;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Where a project's code lives and how designguard classifies it.
 *
 * @param packages         packages to check, including subpackages
 * @param sourceRoots      production source roots
 * @param testSourceRoots  test source roots; missing ones are skipped
 * @param classDirectories directories of compiled classes; empty means "import the packages from the classpath"
 * @param wiringPackages   ArchUnit package patterns of wiring code, which may create and hold anything
 * @param impurePackages   extra ArchUnit package patterns whose use makes a class service-like, e.g. SDK clients
 * @param javaRelease      the Java version the code targets; null means "read it from the class files"
 */
public record ProjectSettings(
        List<String> packages, List<Path> sourceRoots, List<Path> testSourceRoots, List<Path> classDirectories,
        List<String> wiringPackages, List<String> impurePackages, @Nullable Integer javaRelease) {

    private static final int OLDEST_RELEASE = 8;

    /**
     * Copies all lists.
     *
     * @throws NullPointerException     if any list or element is null
     * @throws IllegalArgumentException if no package is given, or the Java version is below 8
     */
    public ProjectSettings {
        if (packages.isEmpty()) {
            throw new IllegalArgumentException("pass at least one package to check, e.g. new DesignGuard(\"com.shop\")");
        }
        if (javaRelease != null && javaRelease < OLDEST_RELEASE) {
            throw new IllegalArgumentException("forJava needs Java " + OLDEST_RELEASE + " or later, was " + javaRelease);
        }
        packages = List.copyOf(packages);
        sourceRoots = List.copyOf(sourceRoots);
        testSourceRoots = List.copyOf(testSourceRoots);
        classDirectories = List.copyOf(classDirectories);
        wiringPackages = List.copyOf(wiringPackages);
        impurePackages = List.copyOf(impurePackages);
    }

    /**
     * Maven and Gradle defaults: {@code src/main/java}, {@code src/test/java}, classes from the classpath.
     *
     * @param packages packages to check
     * @return the settings
     */
    public static ProjectSettings forPackages(List<String> packages) {
        return new ProjectSettings(packages, List.of(Path.of("src", "main", "java")),
                List.of(Path.of("src", "test", "java")), List.of(), List.of(), List.of(), null);
    }

    /**
     * Replaces the production source roots.
     *
     * @param roots source roots
     * @return new settings
     */
    public ProjectSettings withSourceRoots(List<Path> roots) {
        return new ProjectSettings(packages, roots, testSourceRoots, classDirectories, wiringPackages, impurePackages,
                javaRelease);
    }

    /**
     * Replaces the test source roots.
     *
     * @param roots test source roots
     * @return new settings
     */
    public ProjectSettings withTestSourceRoots(List<Path> roots) {
        return new ProjectSettings(packages, sourceRoots, roots, classDirectories, wiringPackages, impurePackages,
                javaRelease);
    }

    /**
     * Reads compiled classes from directories instead of the classpath.
     *
     * @param directories class directories
     * @return new settings
     */
    public ProjectSettings withClassDirectories(List<Path> directories) {
        return new ProjectSettings(packages, sourceRoots, testSourceRoots, directories, wiringPackages, impurePackages,
                javaRelease);
    }

    /**
     * Adds wiring packages.
     *
     * @param patterns ArchUnit package patterns
     * @return new settings
     */
    public ProjectSettings withWiringPackages(List<String> patterns) {
        return new ProjectSettings(packages, sourceRoots, testSourceRoots, classDirectories,
                concat(wiringPackages, patterns), impurePackages, javaRelease);
    }

    /**
     * Adds impure packages.
     *
     * @param patterns ArchUnit package patterns
     * @return new settings
     */
    public ProjectSettings withImpurePackages(List<String> patterns) {
        return new ProjectSettings(packages, sourceRoots, testSourceRoots, classDirectories, wiringPackages,
                concat(impurePackages, patterns), javaRelease);
    }

    /**
     * Sets the Java version the code targets, instead of reading it from the class files.
     *
     * @param release the Java version, e.g. 17
     * @return new settings
     */
    public ProjectSettings withJavaRelease(int release) {
        return new ProjectSettings(packages, sourceRoots, testSourceRoots, classDirectories, wiringPackages,
                impurePackages, release);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }
}
