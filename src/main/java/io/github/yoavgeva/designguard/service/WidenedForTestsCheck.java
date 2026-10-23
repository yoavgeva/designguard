package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds methods and constructors widened for tests: package-private or protected, never called by production code,
 * but called by tests in their package, which reach inside the class instead of testing it through its API. Code
 * nothing calls is left to {@link UnusedPublicCodeCheck}; overrides, annotated members and wiring code are skipped.
 * Test calls are matched by name, argument count and, for constructors, the created type.
 */
public final class WidenedForTestsCheck {

    private static final String RULE = "no-widened-for-tests";

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        WiringCode wiring = code.wiring();
        SourceTypes types = new SourceTypes(classes);
        Map<String, List<ParsedFile>> testsByPackage = code.testSources().files().stream()
                .collect(Collectors.groupingBy(ParsedFile::packageName));
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && !wiring.contains(type))
                .flatMap(type -> type.getCodeUnits().stream()
                        .filter(unit -> isWidened(type, unit) && unit.getAccessesToSelf().isEmpty())
                        .filter(unit -> testsCall(type, unit, testsByPackage.getOrDefault(type.getPackageName(), List.of()),
                                types))
                        .map(unit -> violation(classes, type, unit)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean isWidened(JavaClass type, JavaCodeUnit unit) {
        Set<JavaModifier> modifiers = unit.getModifiers();
        boolean constructor = isConstructor(unit);
        return (constructor || unit.isMethod())
                && !modifiers.contains(JavaModifier.PUBLIC) && !modifiers.contains(JavaModifier.PRIVATE)
                && !modifiers.contains(JavaModifier.SYNTHETIC) && !modifiers.contains(JavaModifier.BRIDGE)
                && unit.getAnnotations().isEmpty()
                && (constructor || !Hierarchy.isInherited(type, unit));
    }

    private static boolean testsCall(JavaClass type, JavaCodeUnit unit, List<ParsedFile> tests, SourceTypes types) {
        int arguments = unit.getRawParameterTypes().size();
        if (isConstructor(unit)) {
            return tests.stream().anyMatch(test -> test.unit().findAll(ObjectCreationExpr.class).stream()
                    .anyMatch(creation -> creation.getArguments().size() == arguments
                            && types.resolve(test.unit(), creation.getType().getNameWithScope()).filter(type::equals).isPresent()));
        }
        return tests.stream().anyMatch(test -> test.unit().findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> call.getNameAsString().equals(unit.getName()) && call.getArguments().size() == arguments));
    }

    private static boolean isConstructor(JavaCodeUnit unit) {
        return JavaConstructor.CONSTRUCTOR_NAME.equals(unit.getName());
    }

    private static Violation violation(ProjectClasses classes, JavaClass type, JavaCodeUnit unit) {
        String owner = ProjectClasses.nameOf(type);
        String name = isConstructor(unit)
                ? owner + unit.getRawParameterTypes().stream().map(JavaClass::getSimpleName)
                        .collect(Collectors.joining(", ", "(", ")"))
                : owner + "." + unit.getName() + "()";
        String visibility = unit.getModifiers().contains(JavaModifier.PROTECTED) ? "protected" : "package-private";
        return classes.violation(RULE, type, unit.getSourceCodeLocation().getLineNumber(),
                name + " is " + visibility + ", and only tests call it",
                "test through the public API, or move it into a class of its own whose API tests and production both use");
    }
}
