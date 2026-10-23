package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.controller.DesignGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/** Writes fixture sources and compiles them, so rules that read bytecode run on real classes. */
public final class CompiledProject {

    private final SourceTree tree;
    private final SourceTree testTree;
    private final Path sourceRoot;
    private final Path testRoot;
    private final Path classesDirectory;

    /**
     * Uses {@code directory/src} for sources, {@code directory/test} for test sources and
     * {@code directory/classes} for compiled classes.
     *
     * @param directory an empty directory, usually a JUnit {@code @TempDir}
     */
    public CompiledProject(Path directory) {
        this.sourceRoot = directory.resolve("src");
        this.testRoot = directory.resolve("test");
        this.classesDirectory = directory.resolve("classes");
        this.tree = new SourceTree(sourceRoot);
        this.testTree = new SourceTree(testRoot);
    }

    /**
     * Adds a source file.
     *
     * @param typeName the top-level type's simple name
     * @param source   the full file content
     * @return this project, for chaining
     */
    public CompiledProject add(String typeName, String source) {
        tree.add(typeName, source);
        return this;
    }

    /**
     * Adds a test source file. Test sources are parsed, not compiled.
     *
     * @param typeName the top-level type's simple name
     * @param source   the full file content
     * @return this project, for chaining
     */
    public CompiledProject addTest(String typeName, String source) {
        testTree.add(typeName, source);
        return this;
    }

    /**
     * Compiles every source and returns a guard over the given packages, reading these sources and classes.
     *
     * @param packages packages to check
     * @return the guard
     */
    public DesignGuard guard(String... packages) {
        compile();
        return new DesignGuard(packages).withSourceRoots(sourceRoot).withTestSourceRoots(testRoot)
                .withClassesFrom(classesDirectory);
    }

    private void compile() {
        List<String> arguments = new ArrayList<>(List.of(
                "-d", classesDirectory.toString(), "--release", "25", "-proc:none", "-Xlint:none"));
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors, arguments.toArray(String[]::new));
        if (status != 0) {
            throw new IllegalStateException("fixture does not compile:\n" + errors.toString(StandardCharsets.UTF_8));
        }
    }
}
