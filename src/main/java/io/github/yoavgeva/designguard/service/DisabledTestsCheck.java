package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds disabled tests: JUnit's {@code @Disabled} and {@code @Ignore} on a test or its class, and TestNG's
 * {@code @Test(enabled = false)}. Optionally allows {@code @Disabled("reason")} with a non-blank reason.
 */
public final class DisabledTestsCheck {

    private static final String RULE = "no-disabled-tests";
    private static final String DISABLED = "Disabled";
    private static final String IGNORE = "Ignore";

    private final boolean allowWithReason;

    /**
     * Configures the check.
     *
     * @param allowWithReason whether a disabled test that states its reason is allowed
     */
    public DisabledTestsCheck(boolean allowWithReason) {
        this.allowWithReason = allowWithReason;
    }

    /**
     * Finds disabled tests in the sources the filter selects.
     *
     * @param sources the parsed test sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .flatMap(file -> Stream.concat(disabledClasses(file, filter), disabledMethods(file, filter)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private Stream<Violation> disabledClasses(ParsedFile file, ClassFilter filter) {
        return file.unit().findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> isDisabled(type) && Scopes.selected(filter, file, type))
                .map(type -> Scopes.violation(RULE, file, type.getName(),
                        "test class " + type.getNameAsString() + " is disabled", fix()));
    }

    private Stream<Violation> disabledMethods(ParsedFile file, ClassFilter filter) {
        return file.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> UnitTestMethods.isTest(method) && !insideDisabledClass(method))
                .filter(method -> (isDisabled(method) || isDisabledInTestNg(method)) && Scopes.selected(filter, file, method))
                .map(method -> Scopes.violation(RULE, file, method.getName(),
                        "test " + Scopes.describe(method) + " is disabled", fix()));
    }

    private boolean isDisabled(NodeWithAnnotations<?> member) {
        return Stream.of(DISABLED, IGNORE)
                .flatMap(name -> UnitTestMethods.annotation(member, name).stream())
                .anyMatch(annotation -> !allowWithReason || !hasReason(annotation));
    }

    private boolean insideDisabledClass(MethodDeclaration method) {
        return Scopes.nearest(method, ClassOrInterfaceDeclaration.class).map(this::isDisabled).orElse(false);
    }

    private static boolean isDisabledInTestNg(MethodDeclaration method) {
        return UnitTestMethods.annotation(method, "Test")
                .flatMap(annotation -> AnnotationValues.attribute(annotation, "enabled"))
                .map(value -> value instanceof BooleanLiteralExpr literal && !literal.getValue())
                .orElse(false);
    }

    private static boolean hasReason(AnnotationExpr annotation) {
        return AnnotationValues.strings(annotation, "value").stream().anyMatch(reason -> !reason.isBlank());
    }

    private String fix() {
        return allowWithReason
                ? "fix and re-enable it, or say why it's disabled: @Disabled(\"reason\")"
                : "fix and re-enable it, or delete it if it no longer matters";
    }
}
