package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds accessors that change state: {@code get}, {@code is} and {@code has} methods that assign a field of their
 * class, change the collection or atomic it holds, or call a method of the class that does. Callers read accessors as
 * queries, so a hidden change surprises them (command-query separation). Lazy initialization, a change made only while
 * a field is still {@code null} or a flag still {@code false} that sets it, double-checked locking included, is fine,
 * and so is a flag set only while the method runs. So are {@code getAnd...} methods, which say they change something,
 * a cursor's {@code hasNext()}, which may read ahead, void methods, which are commands whatever their name, a
 * builder's setters, which take a value and return the builder, and methods implementing an interface from outside the
 * project, which dictates their name.
 */
public final class AccessorSideEffectsCheck {

    private static final String RULE = "accessors-change-nothing";
    private static final Pattern ACCESSOR = Pattern.compile("(get|is|has)[A-Z].*");
    private static final String CHANGING_GETTER = "getAnd";
    private static final Set<String> READS_AHEAD = Set.of("hasNext", "hasPrevious", "hasMoreElements", "hasMoreTokens");

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
                        .filter(method -> isAccessor(classes, type, method) && classes.selected(filter, method))
                        .flatMap(method -> classes.bytecode().changesMadeBy(method).stream().limit(1)
                                .map(change -> classes.violation(RULE, type, change.line(),
                                        ProjectClasses.nameOf(type) + "." + method.getName() + "() changes the field '"
                                                + change.field() + "'"
                                                + (change.through().isEmpty() ? "" : " through " + change.through() + "()"),
                                        "keep get, is and has methods free of side effects: move the change into a"
                                                + " command method, or compute the value without storing it"))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean isAccessor(ProjectClasses classes, JavaClass type, JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        return ACCESSOR.matcher(method.getName()).matches() && !method.getName().startsWith(CHANGING_GETTER)
                && !READS_AHEAD.contains(method.getName()) && !method.getRawReturnType().isEquivalentTo(void.class)
                && !modifiers.contains(JavaModifier.STATIC) && !modifiers.contains(JavaModifier.SYNTHETIC)
                && !isFluentSetter(type, method) && !implementsOutsideApi(classes, type, method);
    }

    /** A method that takes a value and returns its own type, as a builder's {@code isBound(boolean)} does, sets it. */
    private static boolean isFluentSetter(JavaClass type, JavaMethod method) {
        return !method.getRawParameterTypes().isEmpty() && method.getRawReturnType().equals(type);
    }

    /**
     * Whether the method implements one that a supertype from outside the project declares, such as JDBC's
     * {@code Statement.getResultSet()}: its name, and what callers expect of it, come from there.
     */
    private static boolean implementsOutsideApi(ProjectClasses classes, JavaClass type, JavaMethod method) {
        return Stream.concat(type.getAllRawSuperclasses().stream(), type.getAllRawInterfaces().stream())
                .filter(supertype -> !classes.isProject(supertype))
                .flatMap(supertype -> supertype.getMethods().stream())
                .anyMatch(declared -> declared.getName().equals(method.getName())
                        && declared.getRawParameterTypes().equals(method.getRawParameterTypes()));
    }
}
