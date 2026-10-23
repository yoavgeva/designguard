package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.yoavgeva.designguard.model.DesignGuardException;
import io.github.yoavgeva.designguard.model.JavaFeature;
import io.github.yoavgeva.designguard.model.ProjectSettings;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

        /** Java N writes class files of major version N + 44. */
        private static final int CLASS_FILE_VERSION_OFFSET = 44;

        private final ProjectSettings projectSettings;
        private final Memo<ProjectClasses> importedClasses;
        private final Memo<JavaSources> parsedSources;
        private final Memo<JavaSources> parsedTestSources;
        private final Memo<ServiceLikeClasses> classification;
        private final Memo<Integer> release;

        OnDisk(ProjectSettings settings) {
            this.projectSettings = settings;
            this.importedClasses = new Memo<>(() -> readClasses(settings));
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

        private static ProjectClasses readClasses(ProjectSettings settings) {
            JavaClasses classes = importClasses(settings);
            Map<String, String> sourceFiles = classes.stream()
                    .map(ProjectClasses::relativeSourcePath)
                    .distinct()
                    .collect(Collectors.toMap(relative -> relative, relative -> locate(settings.sourceRoots(), relative)));
            return new ProjectClasses(classes, sourceFiles);
        }

        private static int releaseOf(ProjectSettings settings, Memo<ProjectClasses> classes) {
            Integer configured = settings.javaRelease();
            if (configured != null) {
                return configured;
            }
            return classes.get().stream()
                    .filter(JavaClass::isTopLevelClass)
                    .flatMap(type -> type.getSource().stream())
                    .mapToInt(source -> majorVersion(source.getUri()))
                    .filter(major -> major > CLASS_FILE_VERSION_OFFSET)
                    .map(major -> major - CLASS_FILE_VERSION_OFFSET)
                    .min()
                    .orElseGet(() -> Runtime.version().feature());
        }

        /** The class file's major version, or 0 if it can't be read. */
        private static int majorVersion(URI classFile) {
            try (DataInputStream in = new DataInputStream(classFile.toURL().openStream())) {
                in.skipBytes(Integer.BYTES + Short.BYTES);
                return in.readUnsignedShort();
            } catch (IOException e) {
                return 0;
            }
        }

        /** The source file under the first root that has it, or the relative path if none does. */
        private static String locate(List<Path> sourceRoots, String relative) {
            return sourceRoots.stream()
                    .map(root -> root.resolve(relative))
                    .filter(Files::exists)
                    .findFirst()
                    .map(Path::toString)
                    .orElse(relative);
        }

        private static JavaClasses importClasses(ProjectSettings settings) {
            ClassFileImporter importer = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS);
            if (settings.classDirectories().isEmpty()) {
                return importer.importPackages(settings.packages());
            }
            List<String> patterns = settings.packages().stream().map(name -> name + "..").toList();
            return importer.importPaths(settings.classDirectories())
                    .that(JavaClass.Predicates.resideInAnyPackage(patterns.toArray(String[]::new)));
        }
    }
}
