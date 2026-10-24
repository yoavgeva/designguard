package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a call may run: its target, or the method a superclass declares if the target's class inherits it, plus every
 * override of it in a project subclass. Each target's overrides are found once, since many calls share a target and
 * finding them means scanning its subclasses.
 */
final class Overrides {

    private final Map<String, Set<String>> byTarget = new ConcurrentHashMap<>();

    /**
     * The code units a call to the target may run.
     *
     * @param target what the call names
     * @return the full names of the target, the inherited method it names, and its overrides
     */
    Set<String> of(CodeUnitAccessTarget target) {
        return byTarget.computeIfAbsent(target.getFullName(), name -> Stream.of(Stream.of(name),
                        target.resolveMember().stream().map(JavaCodeUnit::getFullName),
                        target.getOwner().getAllSubclasses().stream()
                                .flatMap(subclass -> subclass.getMethods().stream())
                                .filter(method -> method.getName().equals(target.getName())
                                        && method.getRawParameterTypes().equals(target.getRawParameterTypes()))
                                .map(JavaCodeUnit::getFullName))
                .flatMap(names -> names)
                .collect(Collectors.toUnmodifiableSet()));
    }

    /**
     * The code units with a body a call to the target may run: abstract methods run their implementations instead.
     *
     * @param target     what the call names
     * @param withBodies the full names of the code units that have a body
     * @return the full names of the units with a body among the target, the inherited method and the overrides
     */
    Set<String> implementationsOf(CodeUnitAccessTarget target, Set<String> withBodies) {
        return of(target).stream().filter(withBodies::contains).collect(Collectors.toUnmodifiableSet());
    }
}
