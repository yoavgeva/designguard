package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds constructors and static initializers that do work: they call effectful code, such as I/O, the clock or an
 * injected collaborator, instead of only storing what they are given. Such classes can't be created without that
 * work, so tests can't create them cheaply. Creating objects is left to {@link InjectServicesCheck}; wiring code is
 * exempt.
 */
public final class ConstructorWorkCheck {

    private static final String RULE = "constructors-do-no-work";

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line, at most one per constructor or static initializer
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        ServiceLikeClasses serviceLike = code.serviceLike();
        WiringCode wiring = code.wiring();
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && !wiring.contains(type))
                .flatMap(type -> Stream.concat(type.getConstructors().stream(), type.getStaticInitializer().stream())
                        .filter(unit -> classes.selected(filter, unit))
                        .flatMap(unit -> work(classes, serviceLike, type, unit).stream()))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /** The first effectful call the code unit makes, except creating project objects. */
    private static Optional<Violation> work(
            ProjectClasses classes, ServiceLikeClasses serviceLike, JavaClass type, JavaCodeUnit unit) {
        return classes.bytecode().callsMadeBy(unit)
                .filter(call -> !(ForbiddenCall.CONSTRUCTOR.equals(call.getName()) && classes.isProject(call.getTargetOwner())))
                .filter(call -> serviceLike.runsEffects(call.getTarget()))
                .min(Comparator.comparingInt(JavaAccess::getLineNumber))
                .map(call -> {
                    String described = ForbiddenCall.describe(call.getTarget());
                    String place = JavaConstructor.CONSTRUCTOR_NAME.equals(unit.getName()) ? "constructor" : "static initializer";
                    return classes.violation(RULE, type, call.getLineNumber(),
                            ProjectClasses.nameOf(type) + " does work in its " + place + " through " + described,
                            "pass in what " + described + " produces, or call it from a method when it's needed");
                });
    }
}
