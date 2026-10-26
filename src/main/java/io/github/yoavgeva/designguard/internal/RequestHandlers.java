package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the rules about requests share: which methods handle them, a Spring {@code @Controller} or
 * {@code @RestController} handler or a JAX-RS resource method, and what checks that the user asking may do what they
 * ask: method security, the current user, or code that checks permissions.
 */
final class RequestHandlers {

    private static final Set<String> CONTROLLERS = Set.of("Controller", "RestController");
    private static final Set<String> SPRING_HANDLERS = Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping");
    private static final Set<String> JAX_RS_HANDLERS = Set.of("jakarta.ws.rs.GET", "jakarta.ws.rs.POST", "jakarta.ws.rs.PUT",
            "jakarta.ws.rs.DELETE", "jakarta.ws.rs.PATCH", "javax.ws.rs.GET", "javax.ws.rs.POST", "javax.ws.rs.PUT",
            "javax.ws.rs.DELETE", "javax.ws.rs.PATCH");
    private static final Set<String> SECURED = Set.of("PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "DenyAll",
            "PermitAll");
    private static final Set<String> CURRENT_USER_ANNOTATIONS = Set.of("AuthenticationPrincipal", "CurrentSecurityContext");
    private static final Set<String> CURRENT_USER_TYPES = Set.of("java.security.Principal",
            "org.springframework.security.core.Authentication", "org.springframework.security.core.userdetails.UserDetails",
            "org.springframework.security.oauth2.jwt.Jwt", "org.springframework.security.oauth2.core.user.OAuth2User",
            "jakarta.ws.rs.core.SecurityContext", "javax.ws.rs.core.SecurityContext");
    /** Names of methods that return the current user, or something of theirs, as {@code getCurrentUsername()} does. */
    private static final Pattern CURRENT_USER_CALLS = Pattern.compile(".*(Current|Login|LoggedIn|Authenticated|Principal).*");
    private static final Set<String> OWN_USER_CALLS = Set.of("getUserId", "getUsername", "getUserName", "getUser");
    private static final Set<String> PERMISSION_CHECKS = Set.of(
            "org.springframework.security.core.context.SecurityContextHolder",
            "org.springframework.security.authorization.AuthorizationManager",
            "org.springframework.security.access.PermissionEvaluator");

    private RequestHandlers() {
    }

    /** Whether the method handles requests: a Spring controller's mapped method, or a JAX-RS resource method. */
    static boolean isHandler(JavaClass type, JavaMethod method) {
        List<String> annotations = names(method.getAnnotations());
        return isSpringHandler(type, method) || annotations.stream().anyMatch(JAX_RS_HANDLERS::contains);
    }

    /** Whether the method is a Spring controller's mapped method, whose unannotated object parameters bind the request. */
    static boolean isSpringHandler(JavaClass type, JavaMethod method) {
        return ProjectClasses.annotationNames(type).stream().anyMatch(CONTROLLERS::contains)
                && names(method.getAnnotations()).stream().anyMatch(SPRING_HANDLERS::contains);
    }

    /**
     * Whether something checks that the user asking may make the request: method security on the handler or its class,
     * the current user taken as a parameter or asked for, or a call to code that checks permissions, whose names
     * {@code checking} holds.
     */
    static boolean isChecked(JavaClass type, JavaMethod handler, Set<String> checking) {
        return isSecured(type, handler) || takesTheCurrentUser(handler) || asksForTheCurrentUser(type, handler)
                || checking.contains(handler.getFullName());
    }

    /**
     * Whether the method itself checks who's asking: it carries method security, or takes or asks for the current user,
     * as a helper that loads a record and then checks its owner does.
     */
    static boolean readsTheUser(JavaClass type, JavaMethod method) {
        return isSecured(type, method) || takesTheCurrentUser(method) || asksForTheCurrentUser(type, method);
    }

    /** Whether the handler or its class carries method security, such as {@code @PreAuthorize}. */
    static boolean isSecured(JavaClass type, JavaMethod method) {
        return Stream.concat(names(method.getAnnotations()).stream(), ProjectClasses.annotationNames(type).stream())
                .anyMatch(SECURED::contains);
    }

    private static boolean takesTheCurrentUser(JavaMethod method) {
        return method.getParameters().stream().anyMatch(parameter ->
                names(parameter.getAnnotations()).stream().anyMatch(CURRENT_USER_ANNOTATIONS::contains)
                        || CURRENT_USER_TYPES.stream().anyMatch(parameter.getRawType()::isAssignableTo));
    }

    /**
     * Whether the handler calls a method named for the current user, as {@code SecurityUtils.getCurrentUsername()} is, or
     * its own class's {@code getUserId()}, as a base controller provides.
     */
    private static boolean asksForTheCurrentUser(JavaClass type, JavaMethod handler) {
        return handler.getCallsFromSelf().stream().anyMatch(call -> CURRENT_USER_CALLS.matcher(call.getName()).matches()
                || OWN_USER_CALLS.contains(call.getName()) && call.getTargetOwner().equals(type));
    }

    /**
     * Full names of the methods and constructors that check permissions, or call code that does: annotated as secured,
     * or asking Spring Security's context, an authorization manager or a permission evaluator.
     */
    static Set<String> checkingPermissions(ProjectClasses classes) {
        Overrides overrides = new Overrides();
        List<JavaCodeUnit> units = CallGraph.unitsOf(classes.stream().toList());
        return CallGraph.reaching(units,
                unit -> CallGraph.projectCallees(classes, unit.getCallsFromSelf().stream(),
                        access -> overrides.mayRun(classes.bytecode(), access)),
                unit -> names(unit.getAnnotations()).stream().anyMatch(SECURED::contains)
                        || unit.getCallsFromSelf().stream()
                                .anyMatch(call -> PERMISSION_CHECKS.contains(call.getTargetOwner().getName())));
    }

    /** Simple and qualified names of the annotations. */
    static List<String> names(Set<? extends JavaAnnotation<?>> annotations) {
        return annotations.stream().map(annotation -> annotation.getRawType())
                .flatMap(type -> Stream.of(type.getSimpleName(), type.getName()))
                .toList();
    }
}
