package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds queries made once per item, the N+1 pattern: inside a loop, or in a lambda or method reference handed to a
 * stream or {@code forEach}, code calls a collaborator, an instance of another project type, whose call touches the
 * outside world and returns a value. Commands, which return nothing or are named for a change as {@code save} and
 * {@code delete} are, are left alone, and so are a class's own helpers and static methods, which do per-item work that
 * can't be batched, such as reading files. Wiring code is exempt.
 */
public final class QueriesInLoopsCheck {

    private static final String RULE = "no-queries-in-loops";

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line, one per method and query
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        ServiceLikeClasses serviceLike = code.serviceLike();
        WiringCode wiring = code.wiring();
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && !wiring.contains(type))
                .flatMap(type -> type.getCodeUnits().stream()
                        .filter(unit -> classes.selected(filter, unit))
                        .flatMap(unit -> queries(classes, serviceLike, type, unit)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Stream<Violation> queries(ProjectClasses classes, ServiceLikeClasses serviceLike, JavaClass type,
            JavaCodeUnit unit) {
        return classes.bytecode().perItemAccesses(unit)
                .filter(access -> isQuery(classes, type, access.getTarget()) && serviceLike.sure().runsIo(access))
                .collect(Collectors.toMap(access -> access.getTarget().getFullName(), access -> access,
                        (first, second) -> first.getLineNumber() <= second.getLineNumber() ? first : second,
                        LinkedHashMap::new))
                .values().stream()
                .map(access -> violation(classes, type, unit, access));
    }

    /**
     * An instance method of another project type that returns a value and isn't a command, as {@code save} is though it
     * returns what it stored; the caller checks it touches the outside world.
     */
    private static boolean isQuery(ProjectClasses classes, JavaClass type, CodeUnitAccessTarget target) {
        JavaClass owner = target.getOwner();
        return classes.isProject(owner) && !Hierarchy.isSelfOrNested(type, owner)
                && !ForbiddenCall.CONSTRUCTOR.equals(target.getName())
                && !target.getRawReturnType().isEquivalentTo(void.class)
                && target.resolveMember().filter(member -> member.getModifiers().contains(JavaModifier.STATIC)
                        || member instanceof JavaMethod method && CommandMethods.isCommand(method)).isEmpty();
    }

    private static Violation violation(ProjectClasses classes, JavaClass type, JavaCodeUnit unit,
            JavaCodeUnitAccess<?> access) {
        return classes.violation(RULE, type, access.getLineNumber(), ProjectClasses.nameOf(type) + "." + unit.getName()
                        + "() queries " + ForbiddenCall.describe(access.getTarget()) + " inside a loop, once per item",
                "load what the loop needs with one call before it, such as one query for all the keys");
    }
}
