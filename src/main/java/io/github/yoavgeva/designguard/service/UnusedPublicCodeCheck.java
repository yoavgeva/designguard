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
 * Finds public code nothing in the project uses: public top-level types nothing depends on, and public methods
 * nothing calls or references. Meant for applications. Skipped as likely entry points: wiring code, annotated types
 * and methods, inherited signatures, record accessors, enum helpers and bean accessors ({@code get}/{@code set}/
 * {@code is}).
 */
public final class UnusedPublicCodeCheck {

    private static final String RULE = "no-unused-public-code";
    private static final Pattern BEAN_ACCESSOR = Pattern.compile("^(get|set|is)[A-Z].*");
    private static final Set<String> ENUM_HELPERS = Set.of("values", "valueOf");

    /**
     * Finds unused public code among the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        WiringCode wiring = code.wiring();
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && !wiring.contains(type) && type.getAnnotations().isEmpty())
                .flatMap(type -> isUnusedType(classes, type)
                        ? Stream.of(classes.violation(RULE, type, 0, ProjectClasses.nameOf(type)
                                + " is public, but nothing uses it",
                                "delete it, or make it package-private until something needs it"))
                        : unusedMethods(classes, type))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean isUnusedType(ProjectClasses classes, JavaClass type) {
        return type.isTopLevelClass() && type.getModifiers().contains(JavaModifier.PUBLIC)
                && classes.bytecode().usersOf(type).allMatch(user -> Hierarchy.isSelfOrNested(type, user));
    }

    private static Stream<Violation> unusedMethods(ProjectClasses classes, JavaClass type) {
        return type.getMethods().stream()
                .filter(method -> isPublicApi(type, method) && method.getAccessesToSelf().isEmpty())
                .map(method -> classes.violation(RULE, type, method.getSourceCodeLocation().getLineNumber(),
                        ProjectClasses.nameOf(type) + "." + method.getName() + "() is public, but nothing calls it",
                        "delete it, or make it private until something needs it"));
    }

    private static boolean isPublicApi(JavaClass type, JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        return modifiers.contains(JavaModifier.PUBLIC)
                && !modifiers.contains(JavaModifier.SYNTHETIC)
                && !modifiers.contains(JavaModifier.BRIDGE)
                && method.getAnnotations().isEmpty()
                && !BEAN_ACCESSOR.matcher(method.getName()).matches()
                && !(type.isEnum() && ENUM_HELPERS.contains(method.getName()))
                && !isRecordAccessor(type, method)
                && !Hierarchy.isInherited(type, method);
    }

    private static boolean isRecordAccessor(JavaClass type, JavaMethod method) {
        return type.isRecord() && method.getRawParameterTypes().isEmpty()
                && type.getFields().stream().anyMatch(field -> field.getName().equals(method.getName()));
    }
}
