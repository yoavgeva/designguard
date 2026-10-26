package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds request handlers that change data in a database with nothing checking that the user asking may: a handler of
 * a Spring {@code @Controller} or {@code @RestController}, or a JAX-RS resource method, that surely writes, directly or
 * through project code. Anyone who can reach it can change any record whose id they send. A handler passes when:
 *
 * <ul>
 *   <li>it or its class carries {@code @PreAuthorize}, {@code @PostAuthorize}, {@code @Secured},
 *       {@code @RolesAllowed}, {@code @DenyAll} or {@code @PermitAll};</li>
 *   <li>it takes the current user, as a parameter annotated {@code @AuthenticationPrincipal} or
 *       {@code @CurrentSecurityContext}, or typed {@code Principal}, {@code Authentication} and the like, or asks for
 *       it, calling a method named for it as {@code getCurrentUsername()} and {@code getLoginUser()} are, or its own
 *       class's {@code getUserId()}, for the code it calls to scope the change to;</li>
 *   <li>or it calls code that checks permissions, a method with one of those annotations, or one that asks
 *       {@code SecurityContextHolder}, an {@code AuthorizationManager} or a {@code PermissionEvaluator}.</li>
 * </ul>
 */
public final class AuthorizedWritesCheck {

    private static final String RULE = "writes-are-authorized";
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

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        SureEffects sure = code.serviceLike().sure();
        Memo<Set<String>> checking = new Memo<>(() -> checkingPermissions(classes));
        return classes.stream()
                .filter(type -> classes.selected(filter, type))
                .flatMap(type -> type.getMethods().stream()
                        .filter(method -> classes.selected(filter, method) && isHandler(type, method)
                                && !isSecured(type, method) && !takesTheCurrentUser(method) && !asksForTheCurrentUser(type, method))
                        .flatMap(handler -> unchecked(classes, sure, type, handler)
                                .filter(write -> !checking.get().contains(handler.getFullName())).stream()))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean isHandler(JavaClass type, JavaMethod method) {
        List<String> annotations = names(method.getAnnotations());
        return ProjectClasses.annotationNames(type).stream().anyMatch(CONTROLLERS::contains)
                && annotations.stream().anyMatch(SPRING_HANDLERS::contains)
                || annotations.stream().anyMatch(JAX_RS_HANDLERS::contains);
    }

    private static boolean isSecured(JavaClass type, JavaMethod method) {
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

    /** The handler's first call that surely writes to the database. */
    private static Optional<Violation> unchecked(ProjectClasses classes, SureEffects sure, JavaClass type, JavaMethod handler) {
        return handler.getCallsFromSelf().stream()
                .filter(sure::writesToTheDatabase)
                .min(Comparator.comparingInt(JavaCodeUnitAccess::getLineNumber))
                .map(call -> classes.violation(RULE, type, call.getLineNumber(), ProjectClasses.nameOf(type) + "."
                                + handler.getName() + "() writes to the database through " + ForbiddenCall.describe(call.getTarget())
                                + " with nothing checking that the user asking may",
                        "take the current user, as an @AuthenticationPrincipal parameter, and scope the change to it, or "
                                + "check permissions with @PreAuthorize"));
    }

    /**
     * Full names of the methods and constructors that check permissions, or call code that does: annotated as secured,
     * or asking Spring Security's context, an authorization manager or a permission evaluator.
     */
    private static Set<String> checkingPermissions(ProjectClasses classes) {
        Overrides overrides = new Overrides();
        List<JavaCodeUnit> units = CallGraph.unitsOf(classes.stream().toList());
        return CallGraph.reaching(units,
                unit -> CallGraph.projectCallees(classes, unit.getCallsFromSelf().stream(),
                        access -> overrides.mayRun(classes.bytecode(), access)),
                unit -> names(unit.getAnnotations()).stream().anyMatch(SECURED::contains)
                        || unit.getCallsFromSelf().stream()
                                .anyMatch(call -> PERMISSION_CHECKS.contains(call.getTargetOwner().getName())));
    }

    private static List<String> names(Set<? extends JavaAnnotation<?>> annotations) {
        return annotations.stream().map(annotation -> annotation.getRawType())
                .flatMap(type -> Stream.of(type.getSimpleName(), type.getName()))
                .toList();
    }
}
