package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Map;

/** Where the project's classes have their source files, and which classes the build generated instead. */
final class SourceFiles {

    private final Map<String, String> located;
    private final boolean anyFound;

    /** Uses where each {@link #relativePath(JavaClass) relative path} was found, or the path itself where it wasn't. */
    SourceFiles(Map<String, String> located) {
        this.located = Map.copyOf(located);
        this.anyFound = located.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(entry.getValue()));
    }

    /** The class's source file: the one found in a source root, or its path relative to one. */
    String pathOf(JavaClass javaClass) {
        String relative = relativePath(javaClass);
        return located.getOrDefault(relative, relative);
    }

    /**
     * Whether the build generated the class, as an annotation processor or an OpenAPI generator does: the source roots
     * hold other classes' sources, but not its own. Without any source found, no class counts as generated.
     */
    boolean isGenerated(JavaClass javaClass) {
        return anyFound && relativePath(javaClass).equals(pathOf(javaClass));
    }

    /** The class's source file relative to a source root, e.g. {@code com/shop/Cart.java}. */
    static String relativePath(JavaClass javaClass) {
        String fileName = javaClass.getSourceCodeLocation().getSourceFileName();
        String packagePath = javaClass.getPackageName().replace('.', '/');
        return packagePath.isEmpty() ? fileName : packagePath + "/" + fileName;
    }
}
