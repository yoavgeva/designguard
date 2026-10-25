package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves type names written in a source file to project classes, through the file's imports and package, without a
 * symbol solver: test sources often can't see the compiled classes they test.
 */
final class SourceTypes {

    private final Map<String, JavaClass> bySourceName;

    SourceTypes(ProjectClasses classes) {
        this.bySourceName = classes.stream()
                .collect(Collectors.toMap(SourceTypes::sourceName, type -> type, (first, second) -> first));
    }

    /** The class's name as source writes it, nested classes with a dot: {@code shop.Order.Line}. */
    static String sourceName(JavaClass type) {
        return type.getName().replace('$', '.');
    }

    /** The type's name as written, with its scope but without type arguments, e.g. {@code Order.Line}. */
    static Optional<String> named(Type type) {
        return type.toClassOrInterfaceType().map(ClassOrInterfaceType::getNameWithScope);
    }

    /**
     * The project class a name written in the file refers to: as written, through the file's single-type imports, in
     * the file's package, or through its on-demand imports, in that order.
     */
    Optional<JavaClass> resolve(CompilationUnit unit, String written) {
        String first = written.contains(".") ? written.substring(0, written.indexOf('.')) : written;
        String rest = written.substring(first.length());
        String packagePrefix = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString() + ".").orElse("");
        List<ImportDeclaration> imports = unit.getImports().stream().filter(declaration -> !declaration.isStatic()).toList();
        Stream<String> candidates = Stream.of(
                Stream.of(written),
                imports.stream().filter(declaration -> !declaration.isAsterisk()
                                && declaration.getName().getIdentifier().equals(first))
                        .map(declaration -> declaration.getNameAsString() + rest),
                Stream.of(packagePrefix + written),
                imports.stream().filter(ImportDeclaration::isAsterisk)
                        .map(declaration -> declaration.getNameAsString() + "." + written))
                .flatMap(names -> names);
        return candidates.map(bySourceName::get).filter(type -> type != null).findFirst();
    }
}
