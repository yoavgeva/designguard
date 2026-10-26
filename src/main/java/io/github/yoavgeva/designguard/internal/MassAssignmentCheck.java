package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.beans.Introspector;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds request handlers that bind the request to a type with a privileged property: one that grants access, as
 * {@code role} and {@code admin} do, names an owner, as {@code userId} and {@code tenantId} do, or records a check the
 * server makes, as {@code verified} and {@code confirmedAt} do. Whoever sends the request can set it on their own
 * account, so passing the current user doesn't help. A handler parameter binds the request when it's
 * {@code @RequestBody}, {@code @ModelAttribute} or {@code @BeanParam}, or a project type with no annotation naming
 * another source, as Spring binds a form and JAX-RS a body. A property binds when a public setter, a public field or a
 * record component sets it, and isn't {@code @JsonIgnore}d. A handler that method security or a permission check
 * guards passes: an admin may set roles.
 */
public final class MassAssignmentCheck {

    private static final String RULE = "no-mass-assignment";
    /** Privileged property names, lower case without underscores. */
    private static final Set<String> PRIVILEGED = Set.of("role", "roles", "admin", "isadmin", "superuser", "issuperuser",
            "superadmin", "staff", "isstaff", "permissions", "privileges", "authorities", "ownerid", "userid", "accountid",
            "organizationid", "orgid", "teamid", "tenantid", "verified", "emailverified", "verifiedat", "confirmed",
            "confirmedat", "approved", "hashedpassword", "passwordhash");
    private static final Set<String> BINDING = Set.of("RequestBody", "ModelAttribute", "BeanParam");
    /** Annotations that bind a parameter to something other than the request's body or form. */
    private static final Set<String> OTHER_SOURCES = Set.of("PathVariable", "RequestParam", "RequestHeader", "CookieValue",
            "RequestAttribute", "SessionAttribute", "MatrixVariable", "RequestPart", "AuthenticationPrincipal",
            "CurrentSecurityContext", "PathParam", "QueryParam", "HeaderParam", "CookieParam", "FormParam", "MatrixParam",
            "Context", "Suspended");
    private static final String IGNORED = "JsonIgnore";
    private static final String SETTER = "set";

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        Memo<Set<String>> checking = new Memo<>(() -> RequestHandlers.checkingPermissions(classes));
        return classes.stream()
                .filter(type -> classes.selected(filter, type))
                .flatMap(type -> type.getMethods().stream()
                        .filter(method -> classes.selected(filter, method) && RequestHandlers.isHandler(type, method))
                        .flatMap(handler -> handler.getParameters().stream()
                                .filter(parameter -> binds(parameter) && classes.isProject(parameter.getRawType()))
                                .flatMap(parameter -> violation(classes, type, handler, parameter.getRawType()).stream())
                                .filter(violation -> !RequestHandlers.isSecured(type, handler)
                                        && !checking.get().contains(handler.getFullName()))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /** Whether the parameter takes the request's body or form. */
    private static boolean binds(JavaParameter parameter) {
        List<String> annotations = RequestHandlers.names(parameter.getAnnotations());
        return annotations.stream().anyMatch(BINDING::contains) || annotations.stream().noneMatch(OTHER_SOURCES::contains);
    }

    private static Optional<Violation> violation(ProjectClasses classes, JavaClass type, JavaMethod handler, JavaClass bound) {
        List<String> properties = privileged(bound);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        String listed = list(properties);
        String them = properties.size() == 1 ? "it" : "them";
        return Optional.of(classes.violation(RULE, type, handler.getSourceCodeLocation().getLineNumber(),
                ProjectClasses.nameOf(type) + "." + handler.getName() + "() binds the request to "
                        + ProjectClasses.nameOf(bound) + ", which lets it set " + listed,
                "leave " + listed + " out of " + ProjectClasses.nameOf(bound) + ", or mark " + them + " @JsonIgnore, and set "
                        + them + " from the current user, or in a handler that checks permissions with @PreAuthorize"));
    }

    /** The privileged properties a request can set on the type, sorted by name. */
    private static List<String> privileged(JavaClass type) {
        Stream<String> setters = type.getAllMethods().stream()
                .filter(method -> method.getName().length() > SETTER.length() && method.getName().startsWith(SETTER)
                        && method.getRawParameterTypes().size() == 1 && method.getModifiers().contains(JavaModifier.PUBLIC)
                        && !method.getModifiers().contains(JavaModifier.STATIC) && !ignored(method.getAnnotations()))
                .map(method -> Introspector.decapitalize(method.getName().substring(SETTER.length())));
        Stream<String> fields = type.getAllFields().stream()
                .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC)
                        && (field.getModifiers().contains(JavaModifier.PUBLIC) && !field.getModifiers().contains(JavaModifier.FINAL)
                                || isRecord(field.getOwner())))
                .map(JavaField::getName);
        Set<String> hidden = type.getAllFields().stream().filter(field -> ignored(field.getAnnotations()))
                .map(JavaField::getName).collect(Collectors.toSet());
        return Stream.concat(setters, fields)
                .filter(name -> PRIVILEGED.contains(name.replace("_", "").toLowerCase(Locale.ROOT)) && !hidden.contains(name))
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean ignored(Set<? extends JavaAnnotation<?>> annotations) {
        return RequestHandlers.names(annotations).contains(IGNORED);
    }

    private static boolean isRecord(JavaClass type) {
        return type.getRawSuperclass().map(superclass -> "java.lang.Record".equals(superclass.getName())).orElse(false);
    }

    private static String list(List<String> names) {
        return names.size() == 1 ? names.get(0)
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }
}
