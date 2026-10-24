package io.github.yoavgeva.designguard.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.github.yoavgeva.designguard.model.DesignGuardException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses the {@code .java} files under source roots, with a symbol solver that resolves against those roots, the
 * JDK and the classpath.
 */
final class SourceReader {

    private SourceReader() {
    }

    /**
     * Parses every {@code .java} file under the given source roots.
     *
     * @param roots source roots such as {@code src/main/java}
     * @return the parsed sources, ordered by path
     * @throws DesignGuardException if a root is missing or a file does not parse
     */
    static JavaSources read(List<Path> roots) {
        return read(roots, List.of());
    }

    /**
     * Parses every Java file under the roots, resolving symbols in the other roots too, as tests do in the sources
     * they test.
     *
     * @param roots       the source roots to read
     * @param alsoResolve source roots whose types the files may use, such as the production sources of tests
     * @return the parsed files
     * @throws DesignGuardException if a root is missing or a file can't be read or parsed
     */
    static JavaSources read(List<Path> roots, List<Path> alsoResolve) {
        roots.forEach(SourceReader::requireDirectory);
        JavaParser parser = parser(Stream.concat(roots.stream(), alsoResolve.stream().filter(Files::isDirectory)).toList());
        return JavaSources.of(roots.stream()
                .flatMap(SourceReader::javaFiles)
                .map(file -> parse(parser, file))
                .toList());
    }

    private static void requireDirectory(Path root) {
        if (!Files.isDirectory(root)) {
            throw new DesignGuardException("source root " + root.toAbsolutePath()
                    + " does not exist; pass the project's source roots with withSourceRoots(...)");
        }
    }

    private static Stream<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList().stream();
        } catch (IOException e) {
            throw new DesignGuardException("cannot read source root " + root, e);
        }
    }

    private static ParsedFile parse(JavaParser parser, Path file) {
        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(file);
        } catch (IOException e) {
            throw new DesignGuardException("cannot read " + file, e);
        }
        return result.getResult()
                .filter(unit -> result.isSuccessful())
                .map(unit -> new ParsedFile(file.toString(), unit))
                .orElseThrow(() -> new DesignGuardException("cannot parse " + file + ": "
                        + result.getProblems().stream().map(Problem::getMessage).collect(Collectors.joining("; "))
                        + "; fix the syntax error first"));
    }

    private static JavaParser parser(List<Path> roots) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        roots.forEach(root -> solver.add(new JavaParserTypeSolver(root)));
        solver.add(new ReflectionTypeSolver());
        ClassLoader classLoader = Objects.requireNonNullElse(
                Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader());
        solver.add(new ClassLoaderTypeSolver(classLoader));
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                .setSymbolResolver(new JavaSymbolSolver(solver)));
    }
}
