package io.github.yoavgeva.designguard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/** Writes fixture sources and compiles them, so rules that read bytecode run on real classes. */
public final class CompiledProject {

    /** The long-term-support Java versions designguard runs on. */
    private static final List<Integer> LTS_RELEASES = List.of(17, 21, 25);

    /** A class file starts with the magic number and the minor version, then the major version, two bytes each. */
    private static final int MAJOR_VERSION_OFFSET = 6;

    private final SourceTree tree;
    private final SourceTree testTree;
    private final SourceTree generatedTree;
    private final Path sourceRoot;
    private final Path generatedRoot;
    private final Path testRoot;
    private final Path classesDirectory;
    private int javaRelease = 17;
    private int stampedMajorVersion;

    /**
     * Uses {@code directory/src} for sources, {@code directory/test} for test sources and
     * {@code directory/classes} for compiled classes.
     *
     * @param directory an empty directory, usually a JUnit {@code @TempDir}
     */
    public CompiledProject(Path directory) {
        this.sourceRoot = directory.resolve("src");
        this.generatedRoot = directory.resolve("generated");
        this.testRoot = directory.resolve("test");
        this.classesDirectory = directory.resolve("classes");
        this.tree = new SourceTree(sourceRoot);
        this.testTree = new SourceTree(testRoot);
        this.generatedTree = new SourceTree(generatedRoot);
    }

    /**
     * The Java versions whose class files the bytecode rules are tested on: each LTS from 17 that the running JDK can
     * compile for, and the running JDK's own version. Newer compilers turn the same source into different bytecode,
     * and new versions add shapes, such as switches on types.
     *
     * @return the releases, oldest first
     */
    public static IntStream releases() {
        int newest = Runtime.version().feature();
        return IntStream.concat(
                LTS_RELEASES.stream().mapToInt(Integer::intValue).filter(release -> release < newest), IntStream.of(newest));
    }

    /**
     * Compiles for another Java version than 17, the oldest designguard runs on.
     *
     * @param release the {@code --release} to compile with
     * @return this project, for chaining
     */
    public CompiledProject release(int release) {
        this.javaRelease = release;
        return this;
    }

    /**
     * Rewrites the class files' major version after compiling, as a Java newer than designguard knows would write them.
     *
     * @param majorVersion the class file major version, e.g. 69 for Java 25
     * @return this project, for chaining
     */
    public CompiledProject stampMajorVersion(int majorVersion) {
        this.stampedMajorVersion = majorVersion;
        return this;
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
     * Adds a source file the build generates, as an annotation processor does: compiled with the others, but outside
     * the source roots the guard reads.
     *
     * @param typeName the top-level type's simple name
     * @param source   the full file content
     * @return this project, for chaining
     */
    public CompiledProject addGenerated(String typeName, String source) {
        generatedTree.add(typeName, source);
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
        return configure(packages).build();
    }

    /**
     * Compiles every source and starts configuring a guard over the given packages, reading these sources and
     * classes.
     *
     * @param packages packages to check
     * @return the builder, for further settings
     */
    public DesignGuard.Builder configure(String... packages) {
        compile();
        return DesignGuard.forPackages(packages).withSourceRoots(sourceRoot).withTestSourceRoots(testRoot)
                .withClassesFrom(classesDirectory);
    }

    private void compile() {
        List<String> arguments = new ArrayList<>(List.of(
                "-d", classesDirectory.toString(), "--release", Integer.toString(javaRelease), "-proc:none", "-Xlint:none"));
        for (Path root : List.of(sourceRoot, generatedRoot)) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors, arguments.toArray(String[]::new));
        if (status != 0) {
            throw new IllegalStateException("fixture does not compile:\n" + errors.toString(StandardCharsets.UTF_8));
        }
        if (stampedMajorVersion != 0) {
            stampClassFiles();
        }
    }

    private void stampClassFiles() {
        try (Stream<Path> files = Files.walk(classesDirectory)) {
            for (Path classFile : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                byte[] bytes = Files.readAllBytes(classFile);
                bytes[MAJOR_VERSION_OFFSET] = (byte) (stampedMajorVersion >> Byte.SIZE);
                bytes[MAJOR_VERSION_OFFSET + 1] = (byte) stampedMajorVersion;
                Files.write(classFile, bytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
