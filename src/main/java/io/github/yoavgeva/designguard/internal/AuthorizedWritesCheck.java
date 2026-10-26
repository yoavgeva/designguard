package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        Memo<Set<String>> checking = new Memo<>(() -> RequestHandlers.checkingPermissions(classes));
        return classes.stream()
                .filter(type -> classes.selected(filter, type))
                .flatMap(type -> type.getMethods().stream()
                        .filter(method -> classes.selected(filter, method) && RequestHandlers.isHandler(type, method))
                        .flatMap(handler -> unchecked(classes, sure, type, handler)
                                .filter(write -> !RequestHandlers.isChecked(type, handler, checking.get())).stream()))
                .sorted(Scopes.BY_LOCATION)
                .toList();
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
}
