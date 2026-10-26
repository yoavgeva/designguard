package io.github.yoavgeva.designguard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A rule's example in the README: the code the rule flags, and what changes to fix it. Each code block holds the
 * types of one package, without imports, in package {@code shop} unless it declares another; a block with
 * {@code @Test} in it is test code. A type in the fix replaces the flagged type of the same name, and the other
 * flagged types stay.
 *
 * @param id          the rule's id, as the README names it
 * @param recommended whether the README says {@code recommended()} runs the rule
 * @param flagged     the code blocks the rule flags
 * @param fix         the code blocks that change to fix it
 */
record ReadmeExample(String id, boolean recommended, List<String> flagged, List<String> fix) {

    /** A rule's section: its heading names the rule's method, and the next heading ends it. */
    private static final Pattern SECTION = Pattern.compile("^#### `(\\w+)`\\n(.*?)(?=^#|\\z)",
            Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern RULE_LINE = Pattern.compile("^`GUARD\\.[^`]+` · `([a-z-]+)`( · recommended)?$",
            Pattern.MULTILINE);
    private static final Pattern JAVA_BLOCK = Pattern.compile("^```java\\n(.*?)^```$", Pattern.MULTILINE | Pattern.DOTALL);
    private static final String FIX_MARK = "<details>";
    /** A top-level type: declared at the start of a line, where nested types are indented. */
    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "^(?:(?:public|abstract|final|sealed|non-sealed)\\s+)*(?:class|interface|record|enum|@interface)\\s+(\\w+)",
            Pattern.MULTILINE);
    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);$", Pattern.MULTILINE);
    private static final Pattern LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])'");
    private static final String SHOP = "shop";
    private static final String PACKAGE_INFO = "package-info";

    private static final List<String> JDK_PACKAGES = List.of("java.io", "java.math", "java.net", "java.net.http",
            "java.nio.charset", "java.nio.file", "java.sql", "java.time", "java.util", "java.util.concurrent",
            "java.util.function", "java.util.stream");
    private static final String TEST_IMPORTS = """
            import static org.assertj.core.api.Assertions.assertThat;
            import static org.awaitility.Awaitility.await;
            import static org.mockito.Mockito.mock;
            import static org.mockito.Mockito.verify;
            import static org.mockito.Mockito.when;

            import org.junit.jupiter.api.Disabled;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.params.ParameterizedTest;
            import org.junit.jupiter.params.provider.ValueSource;
            """;
    /** The framework annotations the examples use, which aren't on the test class path. */
    private static final Map<String, String> FRAMEWORK_STUBS = Map.of(
            "Service", stub("org.springframework.stereotype", "Service"),
            "Entity", stub("jakarta.persistence", "Entity"));

    /**
     * Reads every rule's example from the README.
     *
     * @param readme the README's text
     * @return the examples, by the rule's method name
     */
    static Map<String, ReadmeExample> readAll(String readme) {
        return SECTION.matcher(readme).results()
                .collect(Collectors.toMap(section -> section.group(1), section -> of(section.group(2))));
    }

    /**
     * Compiles the code the rule flags.
     *
     * @param directory an empty directory to compile in
     * @return a guard over it
     */
    DesignGuard flaggedCode(Path directory) {
        return compile(sourceFiles(flagged).toList(), directory);
    }

    /**
     * Compiles the fixed code: the flagged code with the fix's types in place of the ones they replace.
     *
     * @param directory an empty directory to compile in
     * @return a guard over it
     */
    DesignGuard fixedCode(Path directory) {
        return compile(Stream.concat(sourceFiles(flagged), sourceFiles(fix))
                .collect(Collectors.toMap(SourceFile::qualifiedName, file -> file, (replaced, replacement) -> replacement))
                .values(), directory);
    }

    private static ReadmeExample of(String section) {
        Matcher rule = RULE_LINE.matcher(section);
        if (!rule.find()) {
            throw new IllegalArgumentException("no line naming the rule's call and id in:\n" + section);
        }
        int fixFrom = section.indexOf(FIX_MARK);
        if (fixFrom < 0) {
            throw new IllegalArgumentException("no fix in:\n" + section);
        }
        return new ReadmeExample(rule.group(1), rule.group(2) != null, codeBlocks(section.substring(0, fixFrom)),
                codeBlocks(section.substring(fixFrom)));
    }

    private static List<String> codeBlocks(String text) {
        return JAVA_BLOCK.matcher(text).results().map(block -> block.group(1)).toList();
    }

    private static Stream<SourceFile> sourceFiles(List<String> blocks) {
        return blocks.stream().flatMap(SourceFile::of);
    }

    private static DesignGuard compile(Collection<SourceFile> files, Path directory) {
        CompiledProject project = new CompiledProject(directory);
        FRAMEWORK_STUBS.forEach(project::add);
        String imports = imports(files);
        files.forEach(file -> file.addTo(project, imports));
        return project.guard(SHOP);
    }

    /** What every file imports: the JDK packages the examples use, and each of the example's packages. */
    private static String imports(Collection<SourceFile> files) {
        return Stream.concat(JDK_PACKAGES.stream(), files.stream().map(SourceFile::packageName)).distinct().sorted()
                .map(name -> "import " + name + ".*;\n").collect(Collectors.joining());
    }

    private static String stub(String packageName, String name) {
        return "package " + packageName + ";\n\n"
                + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                + "public @interface " + name + " {\n}\n";
    }

    /**
     * One source file of an example: a top-level type, or a package's {@code package-info.java}.
     *
     * @param packageName the package it declares
     * @param name        the type's name, or {@code package-info}
     * @param test        whether it is test code
     * @param imports     the imports its block shows
     * @param body        the type, or the whole {@code package-info.java}
     */
    private record SourceFile(String packageName, String name, boolean test, String imports, String body) {

        static Stream<SourceFile> of(String block) {
            Matcher declared = PACKAGE.matcher(block);
            String packageName = declared.find() ? declared.group(1) : SHOP;
            if (!TOP_LEVEL_TYPE.matcher(block).find()) {
                return Stream.of(new SourceFile(packageName, PACKAGE_INFO, false, "", block));
            }
            boolean test = block.contains("@Test") || block.contains("@ParameterizedTest");
            String imports = block.lines().filter(line -> line.startsWith("import ")).map(line -> line + "\n")
                    .collect(Collectors.joining());
            return topLevelTypes(block).stream().map(type -> {
                Matcher name = TOP_LEVEL_TYPE.matcher(type);
                name.find();
                return new SourceFile(packageName, name.group(1), test, imports, type);
            });
        }

        String qualifiedName() {
            return packageName + "." + name;
        }

        void addTo(CompiledProject project, String projectImports) {
            if (PACKAGE_INFO.equals(name)) {
                project.add(name, body);
                return;
            }
            String source = "package " + packageName + ";\n\n" + projectImports + (test ? TEST_IMPORTS : "")
                    + imports + "\n" + body;
            if (test) {
                project.addTest(name, source);
            } else {
                project.add(name, source);
            }
        }

        /** Splits a block into its top-level types, each with the annotations and comments before it. */
        private static List<String> topLevelTypes(String block) {
            List<String> types = new ArrayList<>();
            StringBuilder type = new StringBuilder();
            int depth = 0;
            for (String line : block.lines()
                    .filter(line -> !line.startsWith("package ") && !line.startsWith("import ")).toList()) {
                if (type.isEmpty() && line.isBlank()) {
                    continue;
                }
                type.append(line).append('\n');
                String code = LITERAL.matcher(line).replaceAll("\"\"").replaceAll("//.*", "");
                depth += (int) (code.chars().filter(c -> c == '{').count() - code.chars().filter(c -> c == '}').count());
                if (depth == 0 && code.contains("}")) {
                    types.add(type.toString());
                    type.setLength(0);
                }
            }
            return types;
        }
    }
}
