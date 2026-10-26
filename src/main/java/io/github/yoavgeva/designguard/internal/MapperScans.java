package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The packages MyBatis scans for mapper interfaces, which it implements with queries: those a project class names in
 * {@code @MapperScan}, as {@code @MapperScan("com.shop.**.mapper")} does, with their subpackages. A pattern's
 * {@code *} stands for part of a package name and {@code **} for any number of names, as in Spring's resource patterns.
 */
final class MapperScans {

    private static final String SCAN = "org.mybatis.spring.annotation.MapperScan";
    private static final String SCAN_LIST = "org.mybatis.spring.annotation.MapperScans";
    private static final Pattern SEPARATORS = Pattern.compile("[,;\\s]+");
    private static final String ANY_NAMES = "**";

    private MapperScans() {
    }

    /**
     * The scanned packages, as patterns of package names.
     *
     * @param classes the project's classes
     * @return a pattern for each package a {@code @MapperScan} names
     */
    static List<Pattern> in(ProjectClasses classes) {
        return classes.stream()
                .flatMap(type -> type.getAnnotations().stream())
                .flatMap(MapperScans::scans)
                .flatMap(MapperScans::packages)
                .filter(name -> !name.isEmpty())
                .distinct()
                .map(MapperScans::pattern)
                .toList();
    }

    /** The {@code @MapperScan}s the annotation is, or holds as {@code @MapperScans} does. */
    private static Stream<JavaAnnotation<?>> scans(JavaAnnotation<?> annotation) {
        String name = annotation.getRawType().getName();
        if (SCAN_LIST.equals(name)) {
            return annotation.get("value").filter(JavaAnnotation[].class::isInstance)
                    .map(value -> Arrays.<JavaAnnotation<?>>stream((JavaAnnotation<?>[]) value))
                    .orElseGet(Stream::empty);
        }
        return SCAN.equals(name) ? Stream.of(annotation) : Stream.empty();
    }

    private static Stream<String> packages(JavaAnnotation<?> scan) {
        Stream<String> named = Stream.of("value", "basePackages")
                .flatMap(attribute -> strings(scan.get(attribute)))
                .flatMap(SEPARATORS::splitAsStream);
        Stream<String> ofClasses = scan.get("basePackageClasses").filter(JavaClass[].class::isInstance)
                .map(value -> Arrays.stream((JavaClass[]) value).map(JavaClass::getPackageName))
                .orElseGet(Stream::empty);
        return Stream.concat(named, ofClasses);
    }

    private static Stream<String> strings(Optional<Object> value) {
        return value.filter(String[].class::isInstance).map(names -> Arrays.stream((String[]) names))
                .orElseGet(Stream::empty);
    }

    /** A package pattern as a regular expression that also matches the subpackages. */
    private static Pattern pattern(String scanned) {
        StringBuilder regex = new StringBuilder();
        boolean first = true;
        for (String name : scanned.split("\\.")) {
            if (ANY_NAMES.equals(name)) {
                regex.append(first ? "(?:[^.]+\\.)*" : "(?:\\.[^.]+)*");
            } else {
                regex.append(first ? "" : "\\.").append(Arrays.stream(name.split("\\*", -1))
                        .map(Pattern::quote).collect(Collectors.joining("[^.]*")));
                first = false;
            }
        }
        return Pattern.compile(regex + "(?:\\..+)?");
    }
}
