package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds checked exceptions: project exception classes that extend {@code Exception} but not
 * {@code RuntimeException}, and non-private methods that declare a checked exception. Methods whose signature is
 * inherited, like {@code AutoCloseable.close()}, are skipped, as is wiring code.
 */
public final class CheckedExceptionCheck {

    private static final String RULE = "no-checked-exceptions";

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
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && !wiring.contains(type))
                .flatMap(type -> Stream.concat(checkedClass(classes, type), declarations(classes, filter, type)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Stream<Violation> checkedClass(ProjectClasses classes, JavaClass type) {
        if (!isChecked(type)) {
            return Stream.empty();
        }
        return Stream.of(classes.violation(RULE, type, 0, ProjectClasses.nameOf(type) + " is a checked exception",
                "extend RuntimeException instead"));
    }

    private static Stream<Violation> declarations(ProjectClasses classes, ClassFilter filter, JavaClass type) {
        return type.getCodeUnits().stream()
                .filter(unit -> classes.selected(filter, unit))
                .filter(unit -> !unit.getModifiers().contains(JavaModifier.PRIVATE)
                        && !unit.getModifiers().contains(JavaModifier.SYNTHETIC)
                        && !Hierarchy.isInherited(type, unit))
                .flatMap(unit -> unit.getThrowsClause().getTypes().stream()
                        .filter(CheckedExceptionCheck::isChecked)
                        .map(thrown -> classes.violation(RULE, type, unit.getSourceCodeLocation().getLineNumber(),
                                describe(type, unit) + " declares the checked exception " + thrown.getSimpleName(),
                                fix(thrown))));
    }

    private static boolean isChecked(JavaClass type) {
        return type.isAssignableTo(Exception.class) && !type.isAssignableTo(RuntimeException.class);
    }

    private static String describe(JavaClass type, JavaCodeUnit unit) {
        String owner = ProjectClasses.nameOf(type);
        return unit instanceof JavaConstructor ? owner + "'s constructor" : owner + "." + unit.getName() + "()";
    }

    private static String fix(JavaClass thrown) {
        return thrown.isAssignableTo("java.io.IOException")
                ? "handle it here, or rethrow it unchecked, e.g. as UncheckedIOException"
                : "handle it here, or rethrow it as an unchecked exception";
    }
}
