package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a call may run: its target, or the method a superclass declares if the target's class inherits it, plus every
 * override of it in a project subclass. A super call, a private method and a static method run only the method named.
 * Each target's overrides are found once, since many calls share a target and finding them means scanning its
 * subclasses.
 */
final class Overrides {

    private final Map<String, Set<String>> byTarget = new ConcurrentHashMap<>();

    /**
     * The code units a call to the target may run, as far as telling effectful classes goes: the target and its
     * overrides. A method the target's class inherits is left out: its own virtual calls would reach every subclass
     * of the class declaring it, not only those of the target's class.
     *
     * @param target what the call names
     * @return the full names of the target and its overrides
     */
    Set<String> of(CodeUnitAccessTarget target) {
        return byTarget.computeIfAbsent(target.getFullName(), name -> {
            boolean overridable = target.resolveMember()
                    .filter(member -> member.getModifiers().contains(JavaModifier.PRIVATE)
                            || member.getModifiers().contains(JavaModifier.STATIC))
                    .isEmpty();
            Stream<String> overrides = overridable ? target.getOwner().getAllSubclasses().stream()
                    .flatMap(subclass -> subclass.getMethods().stream())
                    .filter(method -> method.getName().equals(target.getName())
                            && method.getRawParameterTypes().equals(target.getRawParameterTypes()))
                    .map(JavaCodeUnit::getFullName) : Stream.empty();
            return Stream.concat(Stream.of(name), overrides).collect(Collectors.toUnmodifiableSet());
        });
    }

    /**
     * The code units a call to the target runs when it names its method exactly, as {@code super.describe()} does.
     *
     * @param target what the call names
     * @return the full names of the target and the inherited method it names
     */
    Set<String> named(CodeUnitAccessTarget target) {
        return Stream.concat(Stream.of(target.getFullName()), target.resolveMember().stream().map(JavaCodeUnit::getFullName))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The code units the call may run as the caller's own code: those it names, for a super call, a call on a
     * parameter, whose implementation the caller's caller chose, a call through a
     * {@link Hierarchy#isFunctionalInterface functional interface}, or a call of a
     * {@link Hierarchy#isCollectionMethod collection's method}, or else the target and its overrides.
     *
     * @param bytecode the facts of the class files, which tell super calls and calls on parameters apart
     * @param call     the call or method reference
     * @return the full names of the code units
     */
    Set<String> mayRun(Bytecode bytecode, JavaCodeUnitAccess<?> call) {
        return bytecode.isCall(Bytecode.CallKind.SUPER, call) || bytecode.isCall(Bytecode.CallKind.ON_PARAMETER, call)
                || Hierarchy.isFunctionalInterface(call.getTargetOwner()) || Hierarchy.isCollectionMethod(call.getTarget())
                ? named(call.getTarget()) : of(call.getTarget());
    }

    /**
     * The code units with a body a call may run, the inherited method it names included: abstract methods run their
     * implementations instead.
     *
     * @param bytecode   the facts of the class files, which tell super calls apart
     * @param call       the call
     * @param withBodies the full names of the code units that have a body
     * @return the full names of the units with a body among the target, the inherited method and the overrides
     */
    Set<String> implementationsOf(Bytecode bytecode, JavaCodeUnitAccess<?> call, Set<String> withBodies) {
        Stream<String> overrides = bytecode.isCall(Bytecode.CallKind.SUPER, call) ? Stream.empty() : of(call.getTarget()).stream();
        return Stream.concat(named(call.getTarget()).stream(), overrides)
                .filter(withBodies::contains)
                .collect(Collectors.toUnmodifiableSet());
    }
}
