package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds accessors that change state: {@code get}, {@code is} and {@code has} methods that assign a field of their
 * class. Callers read accessors as queries, so a hidden change surprises them (command-query separation). Lazy
 * initialization, assigning a field the method reads and checks for {@code null}, double-checked locking included, is
 * fine, and so are
 * {@code getAnd...} methods, which say they change something.
 */
public final class AccessorSideEffectsCheck {

    private static final String RULE = "accessors-change-nothing";
    private static final Pattern ACCESSOR = Pattern.compile("(get|is|has)[A-Z].*");
    private static final String CHANGING_GETTER = "getAnd";

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
                .flatMap(type -> type.getMethods().stream()
                        .filter(method -> isAccessor(method) && classes.selected(filter, method))
                        .flatMap(method -> firstChange(classes, type, method).stream()
                                .map(change -> classes.violation(RULE, type, change.getLineNumber(),
                                        ProjectClasses.nameOf(type) + "." + method.getName() + "() changes the field '"
                                                + change.getName() + "'",
                                        "keep get, is and has methods free of side effects: move the change into a"
                                                + " command method, or compute the value without storing it"))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean isAccessor(JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        return ACCESSOR.matcher(method.getName()).matches() && !method.getName().startsWith(CHANGING_GETTER)
                && !modifiers.contains(JavaModifier.STATIC) && !modifiers.contains(JavaModifier.SYNTHETIC);
    }

    /** The first assignment to a field of the class that isn't lazy initialization. */
    private static Optional<JavaFieldAccess> firstChange(ProjectClasses classes, JavaClass type, JavaMethod method) {
        Set<String> lazy = classes.bytecode().nullGuardedReadsIn(method);
        return method.getFieldAccesses().stream()
                .filter(access -> access.getAccessType() == JavaFieldAccess.AccessType.SET
                        && access.getTargetOwner().equals(type) && !lazy.contains(access.getName()))
                .min(Comparator.comparingInt(JavaAccess::getLineNumber));
    }
}
