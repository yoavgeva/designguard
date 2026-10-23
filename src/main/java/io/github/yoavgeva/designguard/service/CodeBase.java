package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.yoavgeva.designguard.model.ProjectSettings;
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

    abstract ProjectSettings settings();

    abstract ProjectClasses projectClasses();

    abstract ServiceLikeClasses serviceLike();

    WiringCode wiring() {
        return new WiringCode(settings().wiringPackages());
    }

    /** Reads classes and sources from disk on first use, and keeps them. */
    private static final class OnDisk extends CodeBase {

        private final ProjectSettings projectSettings;
        private final Memo<ProjectClasses> importedClasses;
        private final Memo<JavaSources> parsedSources;
        private final Memo<JavaSources> parsedTestSources;
        private final Memo<ServiceLikeClasses> classification;

        OnDisk(ProjectSettings settings) {
            this.projectSettings = settings;
            this.importedClasses = new Memo<>(() -> readClasses(settings));
            this.parsedSources = new Memo<>(() -> SourceReader.read(settings.sourceRoots()).inPackages(settings.packages()));
            this.parsedTestSources = new Memo<>(() -> SourceReader.read(
                    settings.testSourceRoots().stream().filter(Files::isDirectory).toList()));
            this.classification = new Memo<>(() -> new ServiceLikeClasses(importedClasses.get(), settings.impurePackages()));
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
        ProjectSettings settings() {
            return projectSettings;
        }

        @Override
        ProjectClasses projectClasses() {
            return importedClasses.get();
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
