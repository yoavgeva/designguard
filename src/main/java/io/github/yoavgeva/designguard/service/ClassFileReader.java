package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.yoavgeva.designguard.model.DesignGuardException;
import io.github.yoavgeva.designguard.model.ProjectSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Imports the project's compiled classes with ArchUnit, from class directories or the classpath, and reads what
 * ArchUnit doesn't import from their class files.
 */
final class ClassFileReader {

    private ClassFileReader() {
    }

    /**
     * Imports the classes of the configured packages.
     *
     * @param settings where the classes and their sources live
     * @return the classes; none if nothing was found or ArchUnit can't read the class files
     * @throws DesignGuardException if a class file ArchUnit read can't be read again
     */
    static ProjectClasses read(ProjectSettings settings) {
        JavaClasses classes = importClasses(settings);
        Map<String, String> sourceFiles = classes.stream()
                .map(SourceFiles::relativePath)
                .distinct()
                .collect(Collectors.toMap(relative -> relative, relative -> locate(settings.sourceRoots(), relative)));
        Map<String, ClassFileFacts> facts = classes.stream()
                .collect(Collectors.toMap(JavaClass::getName, ClassFileReader::readFacts));
        Bytecode bytecode = new Bytecode(classes, matchedOutside(classes, facts), facts);
        return new ProjectClasses(classes, new SourceFiles(sourceFiles), bytecode);
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

    private static ClassFileFacts readFacts(JavaClass javaClass) {
        return javaClass.getSource().map(source -> {
            URI classFile = source.getUri();
            try (InputStream in = classFile.toURL().openStream()) {
                return ClassFileFacts.of(in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Can't read " + classFile, e);
            } catch (IllegalArgumentException e) {
                throw new DesignGuardException("Can't read " + classFile + ": " + e.getMessage(), e);
            }
        }).orElse(ClassFileFacts.UNREAD);
    }

    /** Classes outside the project that the project's switches match on, from the classpath, so rules see them. */
    private static JavaClasses matchedOutside(JavaClasses classes, Map<String, ClassFileFacts> facts) {
        ClassLoader loader = Objects.requireNonNullElse(Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader());
        List<URL> classFiles = facts.values().stream()
                .flatMap(fact -> fact.switchLabels().stream())
                .map(ClassFileFacts.Reference::className)
                .distinct()
                .filter(name -> !classes.contain(name))
                .map(name -> loader.getResource(name.replace('.', '/') + ".class"))
                .filter(Objects::nonNull)
                .toList();
        return new ClassFileImporter().importUrls(classFiles);
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
}
