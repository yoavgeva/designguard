package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Finds packages whose nullness is unspecified: JSpecify's {@code @NullMarked} is on neither their
 * {@code package-info.java} nor the module. With it, every type in the package is non-null unless marked
 * {@code @Nullable}, and checkers like NullAway can verify that.
 */
public final class NullMarkedCheck {

    private static final String RULE = "null-marked";
    private static final String NULL_MARKED = "NullMarked";
    private static final String PACKAGE_INFO = "package-info.java";

    /**
     * Finds unmarked packages among the sources the filter selects.
     *
     * @param sources the parsed production sources
     * @param filter  which packages to check
     * @return violations ordered by file
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        boolean moduleMarked = sources.files().stream()
                .flatMap(file -> file.unit().getModule().stream())
                .anyMatch(module -> isNullMarked(module.getAnnotations()));
        if (moduleMarked) {
            return List.of();
        }
        Set<String> marked = sources.files().stream()
                .filter(file -> file.path().endsWith(PACKAGE_INFO))
                .filter(file -> file.unit().getPackageDeclaration().map(PackageDeclaration::getAnnotations)
                        .map(NullMarkedCheck::isNullMarked).orElse(false))
                .map(ParsedFile::packageName)
                .collect(Collectors.toSet());
        Map<String, ParsedFile> firstFileByPackage = sources.files().stream()
                .filter(file -> !file.path().endsWith(PACKAGE_INFO) && !file.packageName().isEmpty())
                .collect(Collectors.toMap(ParsedFile::packageName, file -> file,
                        (first, second) -> first.path().compareTo(second.path()) <= 0 ? first : second, TreeMap::new));
        return firstFileByPackage.entrySet().stream()
                .filter(entry -> !marked.contains(entry.getKey()) && filter.selects(entry.getKey(), List.of()))
                .map(entry -> Scopes.violation(RULE, entry.getValue(),
                        entry.getValue().unit().getPackageDeclaration().orElseThrow(),
                        "package " + entry.getKey() + " isn't @NullMarked, so its nullness is unspecified",
                        "add package-info.java to the package: @org.jspecify.annotations.NullMarked package "
                                + entry.getKey() + ";"))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean isNullMarked(List<AnnotationExpr> annotations) {
        return annotations.stream().anyMatch(annotation -> NULL_MARKED.equals(UnitTestMethods.simpleName(annotation)));
    }
}
