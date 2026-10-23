package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.PackageMatcher;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Which code touches the outside world: I/O, the clock, randomness, the environment, threads or processes, or a
 * configured impure package. A method, constructor or lambda is effectful if it uses such an API, reads a field
 * holding such a resource, or calls project code that is effectful; a call through an interface counts as
 * effectful if any implementation is. Using {@code Path}, {@code URI} or {@code File} as data is not I/O.
 */
final class Effects {

    private static final Set<String> IMPURE_PACKAGES =
            Set.of("java.io", "java.nio.file", "java.nio.channels", "java.net", "java.net.http", "java.sql", "javax.sql");
    private static final Set<String> IMPURE_TYPES = Set.of("java.time.Clock", "java.time.InstantSource",
            "java.util.Random", "java.security.SecureRandom", "java.util.concurrent.ThreadLocalRandom",
            "java.util.random.RandomGenerator", "java.lang.Thread", "java.lang.ProcessBuilder", "java.lang.Runtime",
            "java.lang.IO");
    private static final Set<String> IMPURE_CALLS = Set.of("java.lang.System.currentTimeMillis",
            "java.lang.System.nanoTime", "java.lang.System.getenv", "java.lang.System.getProperty",
            "java.lang.Math.random", "java.util.UUID.randomUUID");
    private static final Map<String, Set<String>> IO_ON_VALUE_TYPES = Map.of(
            "java.nio.file.Path", Set.of("toRealPath", "register"),
            "java.nio.file.Paths", Set.of(),
            "java.net.URI", Set.of(),
            "java.net.URL", Set.of("openConnection", "openStream", "getContent"),
            "java.io.File", Set.of("exists", "isFile", "isDirectory", "canRead", "canWrite", "length", "lastModified",
                    "list", "listFiles", "delete", "deleteOnExit", "mkdir", "mkdirs", "createNewFile", "renameTo",
                    "getCanonicalPath", "getCanonicalFile", "setLastModified", "setReadOnly"),
            "java.io.Serializable", Set.of());

    private final List<PackageMatcher> extraImpurePackages;

    Effects(List<String> extraImpurePackages) {
        this.extraImpurePackages = extraImpurePackages.stream().map(PackageMatcher::of).toList();
    }

    /** Whether using this type at all means touching the outside world, like {@code HttpClient} or {@code Random}. */
    boolean isImpureType(JavaClass type) {
        String packageName = type.getPackageName();
        return ServiceLikeClasses.canBeServiceLike(type) && !IO_ON_VALUE_TYPES.containsKey(type.getName())
                && (IMPURE_PACKAGES.contains(packageName)
                        || IMPURE_TYPES.contains(type.getName())
                        || extraImpurePackages.stream().anyMatch(matcher -> matcher.matches(packageName)));
    }

    /** Full names of the effectful methods, constructors and lambdas; a call may run any override of its target. */
    Set<String> effectfulCodeUnits(ProjectClasses classes, List<JavaClass> candidates) {
        return CallGraph.reaching(CallGraph.unitsOf(candidates),
                unit -> CallGraph.projectCallees(classes, accesses(unit), CallGraph::implementations),
                this::isDirectlyEffectful);
    }

    /**
     * Full names of the methods and constructors that touch the outside world when they run, through the calls they
     * make themselves. Calls in the lambdas they create, and methods they only reference, run later if at all.
     */
    Set<String> effectfulWhenRun(ProjectClasses classes, List<JavaClass> candidates) {
        Bytecode bytecode = classes.bytecode();
        return CallGraph.reaching(CallGraph.unitsOf(candidates),
                unit -> CallGraph.projectCallees(classes, bytecode.callsMadeBy(unit), CallGraph::implementations),
                unit -> bytecode.callsMadeBy(unit).anyMatch(call -> isImpure(call.getTarget())));
    }

    /**
     * Full names of the methods and constructors whose own calls touch the outside world when they run: calls they
     * make themselves, followed only to the methods they name. Lambdas they create run later, and effects of
     * implementations they are given, behind interfaces or abstract methods, aren't theirs.
     */
    Set<String> ownEffectsWhenRun(ProjectClasses classes, List<JavaClass> candidates) {
        Bytecode bytecode = classes.bytecode();
        return CallGraph.reaching(CallGraph.unitsOf(candidates),
                unit -> CallGraph.projectCallees(classes, bytecode.callsMadeBy(unit), target -> Stream.of(target.getFullName())),
                unit -> bytecode.callsMadeBy(unit).anyMatch(call -> isImpure(call.getTarget())));
    }

    /**
     * Names of the classes whose own code is effectful: a call counts only through the method it names, not through
     * overrides. Effects of an implementation the class is given, behind an interface or abstract method, don't count.
     */
    Set<String> effectfulOnTheirOwn(ProjectClasses classes, List<JavaClass> candidates) {
        return CallGraph.ownersOf(candidates, CallGraph.reaching(CallGraph.unitsOf(candidates),
                unit -> CallGraph.projectCallees(classes, accesses(unit), target -> Stream.of(target.getFullName())),
                this::isDirectlyEffectful));
    }

    private boolean isDirectlyEffectful(JavaCodeUnit unit) {
        boolean impureAccess = accesses(unit).anyMatch(access -> isImpure(access.getTarget()));
        boolean usesImpureResource = unit.getFieldAccesses().stream()
                .anyMatch(access -> "java.lang.System".equals(access.getTargetOwner().getName())
                        || isImpureType(access.getTarget().getRawType()));
        return impureAccess || usesImpureResource;
    }

    /** Calls, constructor calls and method references the code unit makes. */
    private static Stream<JavaCodeUnitAccess<?>> accesses(JavaCodeUnit unit) {
        return Stream.concat(unit.getCallsFromSelf().stream(), unit.getCodeUnitReferencesFromSelf().stream());
    }

    /** Whether calling the target uses an outside-world API: I/O, the clock, randomness, the environment, threads. */
    boolean isImpure(CodeUnitAccessTarget target) {
        JavaClass owner = target.getOwner();
        String name = owner.getName();
        if (!ServiceLikeClasses.canBeServiceLike(owner)) {
            return false;
        }
        if (IO_ON_VALUE_TYPES.containsKey(name)) {
            return IO_ON_VALUE_TYPES.get(name).contains(target.getName());
        }
        return isImpureType(owner)
                || IMPURE_CALLS.contains(name + "." + target.getName())
                || "java.time".equals(owner.getPackageName()) && "now".equals(target.getName());
    }
}
