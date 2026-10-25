package io.github.yoavgeva.designguard.internal;

import io.github.yoavgeva.designguard.model.DesignGuardException;
import io.github.yoavgeva.designguard.model.JavaFeature;
import io.github.yoavgeva.designguard.model.ProjectSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The code under check: compiled classes for dependency rules, sources for code-shape rules. Rules depend on this
 * abstraction, not on how the code is read.
 */
public abstract sealed class CodeBase {

    private static final Pattern FROZEN_RULE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

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

    /**
     * Where a frozen rule records the violations it accepts: {@code <name>.txt} in the frozen-violations directory.
     *
     * @param name the frozen rule's name, unique among the project's frozen rules, e.g. {@code layers}
     * @return the store
     * @throws IllegalArgumentException if the name isn't a plain file name
     */
    public final ViolationStore frozenViolations(String name) {
        if (!FROZEN_RULE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name a frozen rule with letters, digits, '.', '_' and '-', starting with"
                    + " a letter or digit; was " + name);
        }
        return ViolationStore.inFile(settings().frozenViolations().resolve(name + ".txt"));
    }

    abstract ProjectSettings settings();

    abstract ProjectClasses projectClasses();

    abstract ServiceLikeClasses serviceLike();

    /** The class names the resources packaged with the classes mention, see {@link ResourceNames}. */
    abstract Set<String> resourceNames();

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
        private final Memo<Set<String>> namesInResources;

        OnDisk(ProjectSettings settings) {
            this.projectSettings = settings;
            this.importedClasses = new Memo<>(() -> ClassFileReader.read(settings));
            this.parsedSources = new Memo<>(() -> SourceReader.read(settings.sourceRoots()).inPackages(settings.packages()));
            this.parsedTestSources = new Memo<>(() -> SourceReader.read(
                    settings.testSourceRoots().stream().filter(Files::isDirectory).toList(), settings.sourceRoots()));
            this.classification = new Memo<>(() -> new ServiceLikeClasses(projectClasses(), settings.impurePackages()));
            this.release = new Memo<>(() -> releaseOf(settings, importedClasses));
            this.namesInResources = new Memo<>(() -> ResourceNames.in(projectClasses(), settings.sourceRoots()));
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

        @Override
        Set<String> resourceNames() {
            return namesInResources.get();
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
