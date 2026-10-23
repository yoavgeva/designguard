package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds {@code Optional} used against its design: as a field, as a parameter, or returned as {@code null}.
 * {@code Optional} is meant only as a return type saying "there may be no result".
 */
public final class OptionalUsageCheck {

    private static final String RULE = "optional-usage";
    private static final Set<String> OPTIONALS = Set.of("java.util.Optional", "java.util.OptionalInt",
            "java.util.OptionalLong", "java.util.OptionalDouble");
    private static final Set<String> OPTIONAL_NAMES = Set.of("Optional", "OptionalInt", "OptionalLong", "OptionalDouble");

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        Stream<Violation> compiled = classes.stream()
                .filter(type -> classes.selected(filter, type))
                .flatMap(type -> Stream.concat(fields(classes, filter, type), parameters(classes, filter, type)));
        Stream<Violation> returnedNull = code.sources().files().stream()
                .flatMap(file -> file.unit().findAll(MethodDeclaration.class).stream()
                        .filter(method -> returnsOptional(method) && Scopes.selected(filter, file, method))
                        .filter(OptionalUsageCheck::returnsNull)
                        .map(method -> Scopes.violation(RULE, file, method.getName(),
                                Scopes.describe(method) + " returns null although its result is an Optional",
                                "return Optional.empty() instead of null")));
        return Stream.concat(compiled, returnedNull).sorted(Scopes.BY_LOCATION).toList();
    }

    private static Stream<Violation> fields(ProjectClasses classes, ClassFilter filter, JavaClass type) {
        return type.getFields().stream()
                .filter(field -> OPTIONALS.contains(field.getRawType().getName()) && classes.selected(filter, field))
                .map(field -> classes.violation(RULE, type, 0,
                        ProjectClasses.nameOf(type) + " has a field '" + field.getName() + "' of type Optional",
                        "store the value itself, or null, and return an Optional from its accessor instead"));
    }

    private static Stream<Violation> parameters(ProjectClasses classes, ClassFilter filter, JavaClass type) {
        return type.getCodeUnits().stream()
                .filter(unit -> !unit.getModifiers().contains(JavaModifier.SYNTHETIC) && classes.selected(filter, unit)
                        && unit.getRawParameterTypes().stream().anyMatch(parameter -> OPTIONALS.contains(parameter.getName())))
                .map(unit -> classes.violation(RULE, type, unit.getSourceCodeLocation().getLineNumber(),
                        describe(type, unit) + " takes an Optional parameter",
                        "take the value itself, or offer an overload without it"));
    }

    private static String describe(JavaClass type, JavaCodeUnit unit) {
        String owner = ProjectClasses.nameOf(type);
        return unit instanceof JavaConstructor ? owner + "'s constructor" : owner + "." + unit.getName() + "()";
    }

    private static boolean returnsOptional(MethodDeclaration method) {
        return method.getType() instanceof ClassOrInterfaceType type && OPTIONAL_NAMES.contains(type.getNameAsString());
    }

    private static boolean returnsNull(MethodDeclaration method) {
        return method.findAll(ReturnStmt.class).stream()
                .filter(returned -> Scopes.nearest(returned, LambdaExpr.class).isEmpty())
                .anyMatch(returned -> returned.getExpression().filter(NullLiteralExpr.class::isInstance).isPresent());
    }
}
