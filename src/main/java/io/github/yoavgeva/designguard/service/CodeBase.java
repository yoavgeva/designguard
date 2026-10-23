package io.github.yoavgeva.designguard.service;

import io.github.yoavgeva.designguard.model.DesignGuardException;
import io.github.yoavgeva.designguard.model.JavaFeature;
import io.github.yoavgeva.designguard.model.ProjectSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The code under check: compiled classes for dependency rules, sources for code-shape rules. Rules depend on this
 * abstraction, not on how the code is read.
 */
public abstract sealed class CodeBase {

    /**
     * Describes the code to check, read from disk. Nothing is read until a rule needs it.
     *
     * @param settings where the code lives
     * @return the code base
     */
    public static CodeBase of(ProjectSettings settings) {
        return new OnDisk(settings);
    }

    /**
     * The parsed production sources of the packages.
     *
     * @return the sources
     */
    public abstract JavaSources sources();

    /**
     * The parsed test sources; empty if no test source root exists.
     *
     * @return the test sources
     */
    public abstract JavaSources testSources();

    /**
     * The Java version the checked code targets: the one set with {@code forJava}, else the oldest the class files
     * were compiled for, else the running JVM's.
     *
     * @return the release, e.g. 17
     */
    public abstract int javaRelease();

    /**
     * Fails if the checked code targets a Java version without a feature a rule needs.
     *
     * @param feature the feature
     * @param rule    the rule, for the message, e.g. {@code dataClassesAreRecords()}
     * @throws DesignGuardException if the feature is missing
     */
    public final void requires(JavaFeature feature, String rule) {
        int release = javaRelease();
        if (!feature.isAvailableIn(release)) {
            throw new DesignGuardException(rule + " needs " + feature.description() + " (Java " + feature.release()
                    + "+), but the checked code targets Java " + release + ". Remove the rule, or compile for Java "
                    + feature.release() + " or later. designguard reads the Java version from the class files;"
                    + " forJava(...) overrides it.");
        }
    }

    abstract ProjectSettings settings();

    abstract ProjectClasses projectClasses();

    abstract ServiceLikeClasses serviceLike();

    WiringCode wiring() {
        return new WiringCode(settings().wiringPackages(), javaRelease());
    }

    /** Reads classes and sources from disk on first use, and keeps them. */
    private static final class OnDisk extends CodeBase {

        private final ProjectSettings projectSettings;
        private final Memo<ProjectClasses> importedClasses;
        private final Memo<JavaSources> parsedSources;
        private final Memo<JavaSources> parsedTestSources;
        private final Memo<ServiceLikeClasses> classification;
        private final Memo<Integer> release;

        OnDisk(ProjectSettings settings) {
            this.projectSettings = settings;
            this.importedClasses = new Memo<>(() -> ClassFileReader.read(settings));
            this.parsedSources = new Memo<>(() -> SourceReader.read(settings.sourceRoots()).inPackages(settings.packages()));
            this.parsedTestSources = new Memo<>(() -> SourceReader.read(
                    settings.testSourceRoots().stream().filter(Files::isDirectory).toList()));
            this.classification = new Memo<>(() -> new ServiceLikeClasses(projectClasses(), settings.impurePackages()));
            this.release = new Memo<>(() -> releaseOf(settings, importedClasses));
        }

        @Override
        public JavaSources sources() {
            return parsedSources.get();
        }

        @Override
        public JavaSources testSources() {
            return parsedTestSources.get();
        }

        @Override
        public int javaRelease() {
            return release.get();
        }

        @Override
        ProjectSettings settings() {
            return projectSettings;
        }

        /** The imported classes; none means the rule would check nothing, so it fails instead. */
        @Override
        ProjectClasses projectClasses() {
            ProjectClasses classes = importedClasses.get();
            if (classes.stream().findAny().isEmpty()) {
                List<Path> directories = projectSettings.classDirectories();
                throw new DesignGuardException("Found no compiled classes of packages " + projectSettings.packages()
                        + (directories.isEmpty() ? " on the classpath" : " in " + directories) + ". Check the package"
                        + " names, and that the code is compiled before designguard runs. If it is, its class files may"
                        + " target a newer Java than designguard can read: upgrade designguard, or compile for an"
                        + " older Java.");
            }
            return classes;
        }

        @Override
        ServiceLikeClasses serviceLike() {
            return classification.get();
        }

        private static int releaseOf(ProjectSettings settings, Memo<ProjectClasses> classes) {
            Integer configured = settings.javaRelease();
            if (configured != null) {
                return configured;
            }
            return classes.get().bytecode().oldestRelease().orElseGet(() -> Runtime.version().feature());
        }
    }
}
