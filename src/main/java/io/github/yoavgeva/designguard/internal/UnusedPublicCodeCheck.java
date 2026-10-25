package io.github.yoavgeva.designguard.internal;

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
 * and methods, types a framework finds and creates or the packaged resources name ({@link ResourceNames}), inherited
 * signatures, record accessors, enum helpers, bean accessors ({@code get}/{@code set}/{@code is}) and the methods of
 * JMX interfaces.
 */
public final class UnusedPublicCodeCheck {

    private static final String RULE = "no-unused-public-code";
    private static final String REPOSITORY = "org.springframework.data.repository.Repository";
    private static final Pattern BEAN_ACCESSOR = Pattern.compile("^(get|set|is)[A-Z].*");
    private static final Set<String> ENUM_HELPERS = Set.of("values", "valueOf");
    private static final Pattern MANAGEMENT_INTERFACE = Pattern.compile(".+M(X)?Bean");

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
        Set<String> named = code.resourceNames();
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && !wiring.contains(type) && type.getAnnotations().isEmpty())
                .flatMap(type -> isUnusedType(classes, type) && !named.contains(type.getName())
                        ? Stream.of(classes.violation(RULE, type, 0, ProjectClasses.nameOf(type)
                                + " is public, but nothing uses it",
                                "delete it, or make it package-private until something needs it"))
                        : unusedMethods(classes, type))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean isUnusedType(ProjectClasses classes, JavaClass type) {
        return type.isTopLevelClass() && type.getModifiers().contains(JavaModifier.PUBLIC)
                && classes.bytecode().usersOf(type).allMatch(user -> Hierarchy.isSelfOrNested(type, user))
                && !isFoundByAFramework(classes, type);
    }

    /**
     * Whether a framework finds and creates the type: it extends or implements a type of a library other than the JDK,
     * as a servlet initializer or a Jackson serializer does, or it implements a repository fragment, which Spring Data
     * finds by its {@code Impl} suffix.
     */
    private static boolean isFoundByAFramework(ProjectClasses classes, JavaClass type) {
        return Stream.concat(type.getAllRawSuperclasses().stream(), type.getAllRawInterfaces().stream())
                .anyMatch(supertype -> !classes.isProject(supertype) && !supertype.getName().startsWith("java.")
                        || isRepositoryFragment(classes, type, supertype));
    }

    private static boolean isRepositoryFragment(ProjectClasses classes, JavaClass type, JavaClass fragment) {
        return type.getSimpleName().equals(fragment.getSimpleName() + "Impl")
                && classes.stream().anyMatch(repository -> repository.isInterface()
                        && repository.isAssignableTo(fragment.getName()) && repository.isAssignableTo(REPOSITORY));
    }

    private static Stream<Violation> unusedMethods(ProjectClasses classes, JavaClass type) {
        if (isManagementInterface(type)) {
            return Stream.empty();
        }
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

    /** A JMX interface, named {@code ...MBean} or {@code ...MXBean}: management clients call its methods. */
    private static boolean isManagementInterface(JavaClass type) {
        return type.isInterface() && MANAGEMENT_INTERFACE.matcher(type.getSimpleName()).matches();
    }

    private static boolean isRecordAccessor(JavaClass type, JavaMethod method) {
        return type.isRecord() && method.getRawParameterTypes().isEmpty()
                && type.getFields().stream().anyMatch(field -> field.getName().equals(method.getName()));
    }
}
