package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import io.github.yoavgeva.designguard.model.DesignGuardException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The parsed Java sources of a project, with symbols resolvable against the sources and the classpath. */
public final class JavaSources {

    private final List<ParsedFile> parsedFiles;
    private final Map<String, DeclaredType> typeIndex;

    private JavaSources(List<ParsedFile> files, Map<String, DeclaredType> types) {
        this.parsedFiles = List.copyOf(files);
        this.typeIndex = Map.copyOf(types);
    }

    /**
     * Parses every {@code .java} file under the given source roots.
     *
     * @param roots source roots such as {@code src/main/java}
     * @return the parsed sources, ordered by path
     * @throws DesignGuardException if a root is missing or a file does not parse
     */
    public static JavaSources read(List<Path> roots) {
        List<ParsedFile> files = SourceReader.read(roots);
        return new JavaSources(files, index(files.stream()));
    }

    /**
     * Keeps only files whose package is one of the given packages or below them.
     *
     * @param packages package names, e.g. {@code com.shop}
     * @return a new instance with fewer files
     */
    public JavaSources inPackages(List<String> packages) {
        return new JavaSources(parsedFiles.stream()
                .filter(file -> packages.stream().anyMatch(name -> isInPackage(file.packageName(), name)))
                .toList(), typeIndex);
    }

    /**
     * Combines two sets of sources, e.g. production and test sources.
     *
     * @param other the other sources
     * @return sources holding the files of both
     */
    public JavaSources plus(JavaSources other) {
        Map<String, DeclaredType> types = Stream.concat(typeIndex.entrySet().stream(), other.typeIndex.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
        return new JavaSources(Stream.concat(parsedFiles.stream(), other.parsedFiles.stream()).toList(), types);
    }

    List<ParsedFile> files() {
        return parsedFiles;
    }

    /** The project type of the expression's static type, if the project declares it. */
    Optional<DeclaredType> projectTypeOf(Expression expression) {
        return Resolution.typeOf(expression).flatMap(this::declaration);
    }

    /** A type declared in any of the parsed files, including files outside the checked packages. */
    Optional<DeclaredType> declaration(String qualifiedName) {
        return Optional.ofNullable(typeIndex.get(qualifiedName));
    }

    private static Map<String, DeclaredType> index(Stream<ParsedFile> files) {
        return files
                .flatMap(file -> file.unit().findAll(Node.class, node -> node instanceof TypeDeclaration<?>).stream()
                        .map(node -> new DeclaredType(file, (TypeDeclaration<?>) node)))
                .filter(type -> type.declaration().getFullyQualifiedName().isPresent())
                .collect(Collectors.toMap(type -> type.declaration().getFullyQualifiedName().orElseThrow(),
                        type -> type, (first, second) -> first));
    }

    private static boolean isInPackage(String packageName, String parent) {
        return packageName.equals(parent) || packageName.startsWith(parent + ".");
    }
}
