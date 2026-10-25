package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.PackageMatcher;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which code touches the outside world: code that uses an API {@link JdkEffects} or {@link LibraryEffects} lists, or a
 * configured impure package. A method, constructor or lambda is effectful if it uses such an API, reads a field holding such a
 * resource, or calls project code that is effectful; a call through an interface counts as effectful if any
 * implementation is.
 */
final class Effects {

    private final List<PackageMatcher> extraImpurePackages;
    private final Overrides overrides;
    private final LibraryEffects libraries;

    Effects(List<String> extraImpurePackages, Overrides overrides, ProjectClasses classes) {
        this.extraImpurePackages = extraImpurePackages.stream().map(PackageMatcher::of).toList();
        this.overrides = overrides;
        this.libraries = new LibraryEffects(classes);
    }

    /** Whether using this type at all means touching the outside world, like {@code HttpClient} or {@code Random}. */
    boolean isImpureType(JavaClass type) {
        return JdkEffects.isImpureType(type, extraImpurePackages) || libraries.isClientType(type);
    }

    /** Full names of the effectful methods, constructors and lambdas; a call may run any override of its target. */
    Set<String> effectfulCodeUnits(ProjectClasses classes, List<JavaClass> candidates) {
        return CallGraph.reaching(CallGraph.unitsOf(candidates),
                unit -> CallGraph.projectCallees(classes, accesses(unit), access -> overrides.mayRun(classes.bytecode(), access)),
                unit -> isDirectlyEffectful(classes.bytecode(), unit));
    }

    /**
     * Full names of the methods, constructors and lambdas that touch the outside world by more than reading the clock or
     * randomness: I/O, the environment, threads and the like. A call may run any override of its target.
     */
    Set<String> ioEffectfulCodeUnits(ProjectClasses classes, List<JavaClass> candidates) {
        Bytecode bytecode = classes.bytecode();
        return CallGraph.reaching(CallGraph.unitsOf(candidates),
                unit -> CallGraph.projectCallees(classes, accesses(unit), access -> overrides.mayRun(bytecode, access)),
                unit -> accesses(unit).anyMatch(access -> isImpure(bytecode, access) && !JdkEffects.readsTimeOrChance(access.getTarget()))
                        || readsResource(unit, type -> !JdkEffects.isTimeOrChanceType(type)));
    }

    /**
     * Full names of the methods and constructors that surely make a counted call when they run, through the calls
     * they make themselves: a counted call to an outside-world API, or to project code whose every implementation
     * with a body surely makes one. A call that may run code without one, such as a strategy with implementations of
     * both kinds, doesn't count. Calls in the lambdas they create, and methods they only reference, run later if at all.
     */
    Set<String> surelyWhenRun(ProjectClasses classes, List<JavaClass> candidates, Predicate<JavaCodeUnitAccess<?>> counted) {
        Bytecode bytecode = classes.bytecode();
        List<JavaCodeUnit> units = CallGraph.unitsOf(candidates);
        Set<String> withBodies = CallGraph.withBodies(units);
        Map<String, Set<Set<String>>> calls = units.stream().collect(Collectors.toMap(JavaCodeUnit::getFullName,
                unit -> bytecode.callsMadeBy(unit)
                        .filter(call -> classes.isProject(call.getTargetOwner()))
                        .map(call -> overrides.implementationsOf(bytecode, call, withBodies))
                        .collect(Collectors.toSet()),
                (first, second) -> Stream.concat(first.stream(), second.stream()).collect(Collectors.toSet())));
        Set<String> seeds = units.stream()
                .filter(unit -> bytecode.callsMadeBy(unit).anyMatch(counted))
                .map(JavaCodeUnit::getFullName)
                .collect(Collectors.toSet());
        return CallGraph.spreadThroughEvery(calls, seeds);
    }

    /**
     * Full names of the methods and constructors whose own calls touch the outside world when they run: calls they
     * make themselves, followed only to the methods they name. Lambdas they create run later, and effects of
     * implementations they are given, behind interfaces or abstract methods, aren't theirs.
     */
    Set<String> ownEffectsWhenRun(ProjectClasses classes, List<JavaClass> candidates) {
        Bytecode bytecode = classes.bytecode();
        return CallGraph.reaching(CallGraph.unitsOf(candidates),
                unit -> CallGraph.projectCallees(classes, bytecode.callsMadeBy(unit), access -> overrides.named(access.getTarget())),
                unit -> bytecode.callsMadeBy(unit).anyMatch(call -> isImpure(bytecode, call)));
    }

    /**
     * Names of the classes whose own code is effectful: a call counts only through the method it names, not through
     * overrides. Effects of an implementation the class is given, behind an interface or abstract method, don't count.
     */
    Set<String> effectfulOnTheirOwn(ProjectClasses classes, List<JavaClass> candidates) {
        return CallGraph.ownersOf(candidates, CallGraph.reaching(CallGraph.unitsOf(candidates),
                unit -> CallGraph.projectCallees(classes, accesses(unit), access -> overrides.named(access.getTarget())),
                unit -> isDirectlyEffectful(classes.bytecode(), unit)));
    }

    private boolean isDirectlyEffectful(Bytecode bytecode, JavaCodeUnit unit) {
        return accesses(unit).anyMatch(access -> isImpure(bytecode, access)) || readsResource(unit, type -> true);
    }

    /** Whether the unit reads a field of {@code System}, or one holding a resource of the kinds counted. */
    private boolean readsResource(JavaCodeUnit unit, Predicate<JavaClass> counted) {
        return unit.getFieldAccesses().stream()
                .anyMatch(access -> "java.lang.System".equals(access.getTargetOwner().getName())
                        || isImpureType(access.getTarget().getRawType()) && counted.test(access.getTarget().getRawType()));
    }

    /** Calls, constructor calls and method references the code unit makes. */
    private static Stream<JavaCodeUnitAccess<?>> accesses(JavaCodeUnit unit) {
        return Stream.concat(unit.getCallsFromSelf().stream(), unit.getCodeUnitReferencesFromSelf().stream());
    }

    /**
     * Whether making the call uses an outside-world API: I/O, the clock, randomness, the environment, threads, or the
     * console, as {@code System.out.println(...)} does, or a library's database, HTTP, messaging or mail client.
     * {@code Thread.currentThread().interrupt()} only restores the current thread's interrupt flag, so it doesn't, and
     * neither does reading the rows or binding the parameters of a JDBC result or statement the method is given.
     */
    boolean isImpure(Bytecode bytecode, JavaCodeUnitAccess<?> access) {
        return JdkEffects.isImpure(access.getTarget(), extraImpurePackages)
                && !bytecode.isCall(Bytecode.CallKind.RESTORED_INTERRUPT, access)
                && !(bytecode.isCall(Bytecode.CallKind.ON_PARAMETER, access) && JdkPassiveCalls.readsOrBindsJdbcData(access.getTarget()))
                || bytecode.isCall(Bytecode.CallKind.CONSOLE, access)
                || libraries.isCall(access);
    }

    /**
     * Whether the call does work, which may be slow or fail: it is {@link #isImpure}, other than reading the clock,
     * randomness or the process's configuration, or preparing something that does nothing yet
     * ({@link JdkPassiveCalls#onlyPrepares}).
     */
    boolean doesWork(Bytecode bytecode, JavaCodeUnitAccess<?> access) {
        return isImpure(bytecode, access) && !JdkEffects.readsProcessState(access.getTarget())
                && !JdkPassiveCalls.onlyPrepares(access.getTarget());
    }

    /**
     * Whether the call queries a database or another machine: JDBC, the network, a library client, or a package
     * treated as impure, such as an SDK's. Files, the environment and the console are local.
     */
    boolean queriesRemotely(JavaCodeUnitAccess<?> access) {
        String packageName = access.getTargetOwner().getPackageName();
        return JdkEffects.isImpure(access.getTarget(), extraImpurePackages) && (JdkEffects.reachesRemote(access.getTarget())
                || extraImpurePackages.stream().anyMatch(matcher -> matcher.matches(packageName)))
                || libraries.isCall(access);
    }

}
