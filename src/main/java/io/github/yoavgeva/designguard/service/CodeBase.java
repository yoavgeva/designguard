package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.yoavgeva.designguard.model.ProjectSettings;
import java.nio.file.Files;
import java.util.List;

/** The code under check, read lazily: compiled classes for dependency rules, sources for code-shape rules. */
public final class CodeBase {

    private final ProjectSettings projectSettings;
    private final Memo<ProjectClasses> importedClasses;
    private final Memo<JavaSources> parsedSources;
    private final Memo<JavaSources> parsedTestSources;
    private final Memo<ServiceLikeClasses> classification;

    private CodeBase(ProjectSettings settings) {
        this.projectSettings = settings;
        this.importedClasses = new Memo<>(() -> new ProjectClasses(importClasses(settings), settings.sourceRoots()));
        this.parsedSources = new Memo<>(() -> JavaSources.read(settings.sourceRoots()).inPackages(settings.packages()));
        this.parsedTestSources = new Memo<>(() -> JavaSources.read(
                settings.testSourceRoots().stream().filter(Files::isDirectory).toList()));
        this.classification = new Memo<>(() -> new ServiceLikeClasses(importedClasses.get(), settings.impurePackages()));
    }

    /**
     * Describes the code to check. Nothing is read until a rule needs it.
     *
     * @param settings where the code lives
     * @return the code base
     */
    public static CodeBase of(ProjectSettings settings) {
        return new CodeBase(settings);
    }

    /**
     * The parsed production sources of the packages.
     *
     * @return the sources, parsed on first call
     */
    public JavaSources sources() {
        return parsedSources.get();
    }

    /**
     * The parsed test sources; empty if no test source root exists.
     *
     * @return the test sources, parsed on first call
     */
    public JavaSources testSources() {
        return parsedTestSources.get();
    }

    ProjectSettings settings() {
        return projectSettings;
    }

    ProjectClasses projectClasses() {
        return importedClasses.get();
    }

    ServiceLikeClasses serviceLike() {
        return classification.get();
    }

    WiringCode wiring() {
        return new WiringCode(projectSettings.wiringPackages());
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
