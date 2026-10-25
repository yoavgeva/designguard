package io.github.yoavgeva.designguard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes Java source files, and other files, into a directory, so tests can point designguard at a source root. */
public final class SourceTree {

    private final Path rootDirectory;

    /**
     * Uses a directory as source root.
     *
     * @param root an empty directory, usually a JUnit {@code @TempDir}
     */
    public SourceTree(Path root) {
        this.rootDirectory = root;
    }

    /**
     * Writes a source file, deriving its path from the package declaration and the given type name.
     *
     * @param typeName the top-level type's simple name, which names the file
     * @param source   the full file content, including the package declaration
     * @return this tree, for chaining
     */
    public SourceTree add(String typeName, String source) {
        String packageName = source.lines()
                .filter(line -> line.startsWith("package "))
                .findFirst()
                .map(line -> line.substring("package ".length(), line.indexOf(';')))
                .orElse("");
        String directory = packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/";
        return addFile(directory + typeName + ".java", source);
    }

    /**
     * Writes any file, such as a resource.
     *
     * @param path    the file's path under the root, e.g. {@code META-INF/services/shop.Greeter}
     * @param content the full file content
     * @return this tree, for chaining
     */
    public SourceTree addFile(String path, String content) {
        Path file = rootDirectory.resolve(path);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    /**
     * The source root.
     *
     * @return the directory passed to the constructor
     */
    public Path root() {
        return rootDirectory;
    }
}
