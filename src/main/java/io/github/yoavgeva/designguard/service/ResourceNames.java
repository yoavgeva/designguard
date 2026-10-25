package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.Source;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The class names that the resources a build packages with the classes mention, as {@code META-INF/services} files,
 * logging configuration and Spring's {@code .imports} files do: a framework loads those classes by name. Resources
 * are read where the classes are, as Maven packages them into {@code target/classes}, and from the
 * {@code resources} directory beside each source root, as Gradle keeps them.
 */
final class ResourceNames {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+");
    private static final Set<String> TEXT = Set.of("properties", "xml", "yml", "yaml", "json", "conf", "factories",
            "imports", "txt");
    private static final Path SERVICES = Path.of("META-INF", "services");
    /** Larger files are data, not configuration. */
    private static final long LARGEST = 1L << 20;

    private ResourceNames() {
    }

    /**
     * The names the resources mention.
     *
     * @param classes     the project's classes, whose class directories hold packaged resources
     * @param sourceRoots the production source roots
     * @return every dotted name in the resources, as {@code shop.spi.EnglishGreeter}
     */
    static Set<String> in(ProjectClasses classes, List<Path> sourceRoots) {
        return Stream.concat(classes.stream().flatMap(type -> root(type).stream()).distinct(),
                        sourceRoots.stream().map(root -> root.resolveSibling("resources")))
                .filter(Files::isDirectory)
                .flatMap(ResourceNames::textFiles)
                .flatMap(ResourceNames::names)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The directory the class was read from, above its package's directories; none for a class in a jar. */
    private static Optional<Path> root(JavaClass type) {
        Optional<URI> file = type.getSource().map(Source::getUri).filter(uri -> "file".equals(uri.getScheme()));
        int depth = type.getPackageName().isEmpty() ? 0 : type.getPackageName().split("\\.").length;
        return file.map(Path::of).map(path -> {
            Path root = path.getParent();
            for (int level = 0; level < depth && root != null; level++) {
                root = root.getParent();
            }
            return root;
        });
    }

    private static Stream<Path> textFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> Files.isRegularFile(file) && isText(root.relativize(file))).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static boolean isText(Path relative) {
        String name = relative.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return relative.startsWith(SERVICES) || dot > 0 && TEXT.contains(name.substring(dot + 1));
    }

    /** The names in the file, and a services file's own name, which names the service. */
    private static Stream<String> names(Path file) {
        try {
            if (Files.size(file) > LARGEST) {
                return Stream.empty();
            }
            String text = file.getFileName() + "\n" + new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return NAME.matcher(text).results().map(MatchResult::group).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }
}
