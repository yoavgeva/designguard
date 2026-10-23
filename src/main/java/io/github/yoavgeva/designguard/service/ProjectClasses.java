package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMember;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** The project's compiled classes, what their code uses, and how to point a violation at a class's source file. */
final class ProjectClasses {

    private final JavaClasses classes;
    private final Map<String, String> sourceFiles;
    private final Bytecode compiledCode;

    /**
     * Wraps imported classes.
     *
     * @param classes     the classes
     * @param sourceFiles where each {@link #relativeSourcePath(JavaClass) relative source path} was found
     * @param bytecode    what the classes' code uses and creates
     */
    ProjectClasses(JavaClasses classes, Map<String, String> sourceFiles, Bytecode bytecode) {
        this.classes = classes;
        this.sourceFiles = Map.copyOf(sourceFiles);
        this.compiledCode = bytecode;
    }

    Stream<JavaClass> stream() {
        return classes.stream();
    }

    boolean isProject(JavaClass javaClass) {
        return classes.contain(javaClass.getName());
    }

    Bytecode bytecode() {
        return compiledCode;
    }

    /** Whether the filter selects the class: by its package, and the annotations on it and the classes around it. */
    boolean selected(ClassFilter filter, JavaClass javaClass) {
        return filter.selects(javaClass.getPackageName(), annotationsAround(javaClass).toList());
    }

    /**
     * Whether the filter selects the field, method or constructor: by its class's package, and the annotations on it,
     * its class and the classes around that.
     */
    boolean selected(ClassFilter filter, JavaMember member) {
        List<String> annotations = Stream.concat(names(member.getAnnotations()), annotationsAround(member.getOwner()))
                .toList();
        return filter.selects(member.getOwner().getPackageName(), annotations);
    }

    Violation violation(String rule, JavaClass javaClass, int line, String message, String fix) {
        String relative = relativeSourcePath(javaClass);
        return new Violation(rule, Violation.Severity.ERROR, sourceFiles.getOrDefault(relative, relative), line, message, fix);
    }

    static List<String> annotationNames(JavaClass javaClass) {
        return names(javaClass.getAnnotations()).toList();
    }

    /** Simple and qualified names of the annotations on the class and on every class enclosing it. */
    private static Stream<String> annotationsAround(JavaClass javaClass) {
        return Stream.iterate(Optional.of(javaClass), Optional::isPresent, current -> current.get().getEnclosingClass())
                .map(Optional::get)
                .flatMap(type -> names(type.getAnnotations()));
    }

    private static Stream<String> names(Set<? extends JavaAnnotation<?>> annotations) {
        return annotations.stream()
                .map(JavaAnnotation::getRawType)
                .flatMap(type -> Stream.of(type.getSimpleName(), type.getName()));
    }

    /** A readable name for a class, nested classes included: {@code Outer.Inner}. */
    static String nameOf(JavaClass javaClass) {
        String name = javaClass.getName();
        String packageName = javaClass.getPackageName();
        String local = packageName.isEmpty() ? name : name.substring(packageName.length() + 1);
        return local.replace('$', '.');
    }

    /** The class's source file relative to a source root, e.g. {@code com/shop/Cart.java}. */
    static String relativeSourcePath(JavaClass javaClass) {
        String fileName = javaClass.getSourceCodeLocation().getSourceFileName();
        String packagePath = javaClass.getPackageName().replace('.', '/');
        return packagePath.isEmpty() ? fileName : packagePath + "/" + fileName;
    }
}
