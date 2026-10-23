package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** The project's compiled classes, and how to point a violation at a class's source file. */
final class ProjectClasses {

    private final JavaClasses classes;
    private final List<Path> sourceRoots;

    ProjectClasses(JavaClasses classes, List<Path> sourceRoots) {
        this.classes = classes;
        this.sourceRoots = List.copyOf(sourceRoots);
    }

    JavaClasses all() {
        return classes;
    }

    Stream<JavaClass> stream() {
        return classes.stream();
    }

    boolean isProject(JavaClass javaClass) {
        return classes.contain(javaClass.getName());
    }

    boolean selected(ClassFilter filter, JavaClass javaClass) {
        return filter.selects(javaClass.getPackageName(), annotationNames(javaClass));
    }

    Violation violation(String rule, JavaClass javaClass, int line, String message, String fix) {
        return new Violation(rule, Violation.Severity.ERROR, sourcePath(javaClass), line, message, fix);
    }

    static List<String> annotationNames(JavaClass javaClass) {
        return javaClass.getAnnotations().stream()
                .map(JavaAnnotation::getRawType)
                .flatMap(type -> Stream.of(type.getSimpleName(), type.getName()))
                .toList();
    }

    /** A readable name for a class, nested classes included: {@code Outer.Inner}. */
    static String nameOf(JavaClass javaClass) {
        String name = javaClass.getName();
        String packageName = javaClass.getPackageName();
        String local = packageName.isEmpty() ? name : name.substring(packageName.length() + 1);
        return local.replace('$', '.');
    }

    private String sourcePath(JavaClass javaClass) {
        String fileName = javaClass.getSourceCodeLocation().getSourceFileName();
        String packagePath = javaClass.getPackageName().replace('.', '/');
        String relative = packagePath.isEmpty() ? fileName : packagePath + "/" + fileName;
        return sourceRoots.stream()
                .map(root -> root.resolve(relative))
                .filter(Files::exists)
                .findFirst()
                .map(Path::toString)
                .orElse(relative);
    }
}
