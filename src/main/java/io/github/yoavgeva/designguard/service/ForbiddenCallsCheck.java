package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds calls, constructor calls and method references to APIs a rule forbids outside wiring code, such as the
 * system clock. Each forbidden API is reported once per class, at its first use.
 */
public final class ForbiddenCallsCheck {

    private final String rule;
    private final String verb;
    private final List<ForbiddenCall> forbidden;

    ForbiddenCallsCheck(String rule, String verb, List<ForbiddenCall> forbidden) {
        this.rule = rule;
        this.verb = verb;
        this.forbidden = List.copyOf(forbidden);
    }

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
                .filter(javaClass -> classes.selected(filter, javaClass) && !wiring.contains(javaClass))
                .flatMap(javaClass -> violations(classes, filter, javaClass))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private Stream<Violation> violations(ProjectClasses classes, ClassFilter filter, JavaClass javaClass) {
        return javaClass.getCodeUnitAccessesFromSelf().stream()
                .filter(access -> classes.selected(filter, access.getOrigin()))
                .flatMap(access -> match(access).stream())
                .collect(Collectors.toMap(Match::description, match -> match,
                        (first, second) -> first.line() <= second.line() ? first : second, LinkedHashMap::new))
                .values().stream()
                .map(match -> classes.violation(rule, javaClass, match.line(),
                        ProjectClasses.nameOf(javaClass) + " " + verb + " through " + match.description(), match.fix()));
    }

    private Optional<Match> match(JavaCodeUnitAccess<?> access) {
        return forbidden.stream()
                .filter(call -> call.matches().test(access.getTarget()))
                .findFirst()
                .map(call -> new Match(ForbiddenCall.describe(access.getTarget()), access.getLineNumber(),
                        call.fix().apply(access.getTarget())));
    }

    /** One forbidden use found in a class. */
    private record Match(String description, int line, String fix) {
    }
}
