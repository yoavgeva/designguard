package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.PackageMatcher;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which code touches the outside world: I/O, the clock, randomness, the environment, threads, executors or
 * processes, or a configured impure package. Holding a thread or an executor, or reading the current thread, as
 * {@code Thread.currentThread().getName()} does, isn't an effect; starting, configuring or running work on one is. A
 * method, constructor or lambda is effectful if it uses such an API, reads a field holding such a resource, or calls
 * project code that is effectful; a call through an interface counts as effectful if any implementation is. Using
 * {@code Path}, {@code URI} or {@code File} as data is not I/O, and neither are in-memory streams such as
 * {@code StringWriter}, or wrapping a stream: only creating one on a file name, or an object stream, which reads or
 * writes a header, is.
 */
final class Effects {

    private static final String CONSTRUCTOR = "<init>";
    private static final Set<String> IMPURE_PACKAGES =
            Set.of("java.io", "java.nio.file", "java.nio.channels", "java.net", "java.net.http", "java.sql", "javax.sql");
    private static final Set<String> IMPURE_TYPES = Set.of("java.time.Clock", "java.time.InstantSource",
            "java.util.Random", "java.security.SecureRandom", "java.util.concurrent.ThreadLocalRandom",
            "java.util.random.RandomGenerator", "java.lang.ProcessBuilder", "java.lang.Runtime",
            "java.lang.IO");
    private static final Set<String> IMPURE_CALLS = Set.of("java.lang.System.currentTimeMillis",
            "java.lang.System.nanoTime", "java.lang.System.getenv", "java.lang.System.getProperty",
            "java.lang.Math.random", "java.util.UUID.randomUUID");
    private static final Set<String> HEADER_STREAMS = Set.of("java.io.ObjectInputStream", "java.io.ObjectOutputStream");
    private static final Set<String> RUNS_WORK = Set.of("execute", "submit", "invokeAll", "invokeAny", "schedule",
            "scheduleAtFixedRate", "scheduleWithFixedDelay", "shutdown", "shutdownNow", "close", "awaitTermination");
    private static final Set<String> STARTS_POOLS = Set.of(CONSTRUCTOR, "execute", "submit", "invokeAll", "invokeAny",
            "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay", "shutdown", "shutdownNow", "close",
            "awaitTermination", "invoke");
    /**
     * Types whose values are data, with the methods that are effects: holding a {@code Path}, a {@code Thread} or an
     * {@code ExecutorService} isn't one, but reading the file, starting the thread or running work on it is.
     */
    private static final Map<String, Set<String>> EFFECTS_OF_VALUE_TYPES = Map.ofEntries(
            Map.entry("java.nio.file.Path", Set.of("toRealPath", "register")),
            Map.entry("java.nio.file.Paths", Set.of()),
            Map.entry("java.net.URI", Set.of()),
            Map.entry("java.net.URL", Set.of("openConnection", "openStream", "getContent")),
            Map.entry("java.io.File", Set.of("exists", "isFile", "isDirectory", "canRead", "canWrite", "length",
                    "lastModified", "list", "listFiles", "delete", "deleteOnExit", "mkdir", "mkdirs", "createNewFile",
                    "renameTo", "getCanonicalPath", "getCanonicalFile", "setLastModified", "setReadOnly")),
            Map.entry("java.io.Serializable", Set.of()),
            Map.entry("java.io.ByteArrayInputStream", Set.of()),
            Map.entry("java.io.ByteArrayOutputStream", Set.of()),
            Map.entry("java.io.CharArrayReader", Set.of()),
            Map.entry("java.io.CharArrayWriter", Set.of()),
            Map.entry("java.io.StringReader", Set.of()),
            Map.entry("java.io.StringWriter", Set.of()),
            Map.entry("java.lang.Thread", Set.of(CONSTRUCTOR, "start", "sleep", "join", "interrupt", "interrupted",
                    "yield", "setDaemon", "setName", "setPriority", "setContextClassLoader",
                    "setUncaughtExceptionHandler", "setDefaultUncaughtExceptionHandler", "startVirtualThread",
                    "ofVirtual", "ofPlatform")),
            Map.entry("java.lang.ThreadGroup", Set.of(CONSTRUCTOR, "interrupt", "setDaemon", "setMaxPriority")),
            Map.entry("java.util.Timer", Set.of(CONSTRUCTOR, "schedule", "scheduleAtFixedRate", "cancel", "purge")),
            Map.entry("java.util.concurrent.ThreadFactory", Set.of("newThread")),
            Map.entry("java.util.concurrent.Executors", Set.of("newFixedThreadPool", "newCachedThreadPool",
                    "newSingleThreadExecutor", "newScheduledThreadPool", "newSingleThreadScheduledExecutor",
                    "newWorkStealingPool", "newVirtualThreadPerTaskExecutor", "newThreadPerTaskExecutor")),
            Map.entry("java.util.concurrent.Executor", RUNS_WORK),
            Map.entry("java.util.concurrent.ExecutorService", RUNS_WORK),
            Map.entry("java.util.concurrent.ScheduledExecutorService", RUNS_WORK),
            Map.entry("java.util.concurrent.AbstractExecutorService", RUNS_WORK),
            Map.entry("java.util.concurrent.ThreadPoolExecutor", STARTS_POOLS),
            Map.entry("java.util.concurrent.ScheduledThreadPoolExecutor", STARTS_POOLS),
            Map.entry("java.util.concurrent.ForkJoinPool", STARTS_POOLS));

    private final List<PackageMatcher> extraImpurePackages;
    private final Overrides overrides;

    Effects(List<String> extraImpurePackages, Overrides overrides) {
        this.extraImpurePackages = extraImpurePackages.stream().map(PackageMatcher::of).toList();
        this.overrides = overrides;
    }

    /** Whether the class can be effectful at all: annotations and exceptions never are, they only carry data. */
    static boolean canHaveEffects(JavaClass javaClass) {
        return !javaClass.isAnnotation() && !javaClass.isAssignableTo(Throwable.class);
    }

    /** Whether using this type at all means touching the outside world, like {@code HttpClient} or {@code Random}. */
    boolean isImpureType(JavaClass type) {
        String packageName = type.getPackageName();
        return canHaveEffects(type) && !EFFECTS_OF_VALUE_TYPES.containsKey(type.getName())
                && (IMPURE_PACKAGES.contains(packageName)
                        || IMPURE_TYPES.contains(type.getName())
                        || extraImpurePackages.stream().anyMatch(matcher -> matcher.matches(packageName)));
    }

    /** Full names of the effectful methods, constructors and lambdas; a call may run any override of its target. */
    Set<String> effectfulCodeUnits(ProjectClasses classes, List<JavaClass> candidates) {
        return CallGraph.reaching(CallGraph.unitsOf(candidates),
                unit -> CallGraph.projectCallees(classes, accesses(unit), target -> overrides.of(target).stream()),
                this::isDirectlyEffectful);
    }

    /**
     * Full names of the methods and constructors that surely touch the outside world when they run, through the calls
     * they make themselves: a call to an outside-world API, or to project code whose every implementation with a
     * body surely does. A call that may run code without effects, such as a strategy with implementations of both
     * kinds, doesn't count. Calls in the lambdas they create, and methods they only reference, run later if at all.
     */
    Set<String> surelyEffectfulWhenRun(ProjectClasses classes, List<JavaClass> candidates) {
        Bytecode bytecode = classes.bytecode();
        List<JavaCodeUnit> units = CallGraph.unitsOf(candidates);
        Set<String> withBodies = CallGraph.withBodies(units);
        Map<String, Set<Set<String>>> calls = units.stream().collect(Collectors.toMap(JavaCodeUnit::getFullName,
                unit -> bytecode.callsMadeBy(unit)
                        .map(JavaCall::getTarget)
                        .filter(target -> classes.isProject(target.getOwner()))
                        .map(target -> overrides.implementationsOf(target, withBodies))
                        .collect(Collectors.toSet()),
                (first, second) -> Stream.concat(first.stream(), second.stream()).collect(Collectors.toSet())));
        Set<String> seeds = units.stream()
                .filter(unit -> bytecode.callsMadeBy(unit).anyMatch(call -> isImpure(call.getTarget())))
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
        if (!canHaveEffects(owner)) {
            return false;
        }
        if (EFFECTS_OF_VALUE_TYPES.containsKey(name)) {
            return EFFECTS_OF_VALUE_TYPES.get(name).contains(target.getName());
        }
        if (CONSTRUCTOR.equals(target.getName()) && "java.io".equals(owner.getPackageName())) {
            return opensOnCreation(target);
        }
        return isImpureType(owner)
                || IMPURE_CALLS.contains(name + "." + target.getName())
                || "java.time".equals(owner.getPackageName()) && "now".equals(target.getName());
    }

    /**
     * Whether creating the stream touches the outside world: it opens the file it is given by name, as
     * {@code new FileReader("prices.txt")} does, or reads or writes an object stream's header. Wrapping a stream
     * doesn't.
     */
    private static boolean opensOnCreation(CodeUnitAccessTarget constructor) {
        return HEADER_STREAMS.contains(constructor.getOwner().getName())
                || constructor.getRawParameterTypes().stream().findFirst()
                        .filter(first -> first.isEquivalentTo(String.class) || first.isEquivalentTo(java.io.File.class))
                        .isPresent();
    }
}
