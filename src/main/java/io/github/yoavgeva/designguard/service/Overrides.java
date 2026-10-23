package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a call may run: its target, plus every override of it in a project subclass. Each target's overrides are found
 * once, since many calls share a target and finding them means scanning its subclasses.
 */
final class Overrides {

    private final Map<String, Set<String>> byTarget = new ConcurrentHashMap<>();

    /**
     * The code units a call to the target may run.
     *
     * @param target what the call names
     * @return the full names of the target and its overrides
     */
    Set<String> of(CodeUnitAccessTarget target) {
        return byTarget.computeIfAbsent(target.getFullName(), name -> Stream.concat(Stream.of(name),
                        target.getOwner().getAllSubclasses().stream()
                                .flatMap(subclass -> subclass.getMethods().stream())
                                .filter(method -> method.getName().equals(target.getName())
                                        && method.getRawParameterTypes().equals(target.getRawParameterTypes()))
                                .map(JavaCodeUnit::getFullName))
                .collect(Collectors.toUnmodifiableSet()));
    }
}
