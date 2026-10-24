package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.PackageMatcher;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which JDK APIs touch the outside world: I/O, the clock, randomness, the environment, threads, executors or
 * processes, plus the packages a project treats as impure. Holding a thread or an executor, or reading the current
 * thread, isn't an effect; starting, configuring or running work on one is. Using {@code Path}, {@code URI},
 * {@code File}, an {@code InetAddress} or a {@code java.sql.Timestamp} as data is not I/O, and neither are encoding a
 * URL, in-memory streams such as {@code StringWriter}, arrays, or wrapping a stream: only creating one on a file name,
 * or an object stream, which reads or writes a header, is.
 */
final class JdkEffects {

    private static final String CONSTRUCTOR = "<init>";
    private static final Set<String> IMPURE_PACKAGES =
            Set.of("java.io", "java.nio.file", "java.nio.channels", "java.net", "java.net.http", "java.sql", "javax.sql");
    private static final Set<String> IMPURE_TYPES = Set.of("java.time.Clock", "java.time.InstantSource",
            "java.util.Random", "java.security.SecureRandom", "java.util.concurrent.ThreadLocalRandom",
            "java.util.random.RandomGenerator", "java.lang.ProcessBuilder", "java.lang.Runtime",
            "java.lang.IO");
    private static final Set<String> IMPURE_CALLS = Set.of("java.lang.System.currentTimeMillis",
            "java.lang.System.nanoTime", "java.lang.System.getenv", "java.lang.System.getProperty",
            "java.lang.System.getProperties", "java.lang.Integer.getInteger", "java.lang.Long.getLong",
            "java.lang.Boolean.getBoolean", "java.lang.Math.random", "java.util.UUID.randomUUID",
            "java.util.Calendar.getInstance");
    /** Types whose constructor without parameters reads the clock. */
    private static final Set<String> CLOCK_CONSTRUCTORS = Set.of("java.util.Date", "java.util.GregorianCalendar");
    /** What an {@code InetAddress} does that resolves names or reaches the network. */
    private static final Set<String> RESOLVES = Set.of("getByName", "getAllByName", "getLocalHost", "getHostName",
            "getCanonicalHostName", "isReachable");
    private static final Set<String> HEADER_STREAMS = Set.of("java.io.ObjectInputStream", "java.io.ObjectOutputStream");
    private static final Set<String> RUNS_WORK = Set.of("execute", "submit", "invokeAll", "invokeAny", "schedule",
            "scheduleAtFixedRate", "scheduleWithFixedDelay", "shutdown", "shutdownNow", "close", "awaitTermination");
    private static final Set<String> STARTS_POOLS = Set.of(CONSTRUCTOR, "execute", "submit", "invokeAll", "invokeAny",
            "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay", "shutdown", "shutdownNow", "close",
            "awaitTermination", "invoke");
    /**
     * Types whose values are data, with the methods that are effects: holding a {@code Path}, a {@code Thread} or an
     * {@code ExecutorService} isn't one, but reading the file, starting the thread or running work on it is. The
     * {@code java.net} and {@code java.sql} types listed are values: addresses, encoders and SQL dates and types.
     */
    private static final Map<String, Set<String>> EFFECTS_OF_VALUE_TYPES = Map.ofEntries(
            Map.entry("java.nio.file.Path", Set.of("toRealPath", "register")),
            Map.entry("java.nio.file.Paths", Set.of()),
            Map.entry("java.net.URI", Set.of()),
            Map.entry("java.net.URL", Set.of("openConnection", "openStream", "getContent")),
            Map.entry("java.io.File", Set.of("exists", "isFile", "isDirectory", "canRead", "canWrite", "length",
                    "lastModified", "list", "listFiles", "delete", "deleteOnExit", "mkdir", "mkdirs", "createNewFile",
                    "renameTo", "getCanonicalPath", "getCanonicalFile", "setLastModified", "setReadOnly")),
            Map.entry("java.net.InetAddress", RESOLVES),
            Map.entry("java.net.Inet4Address", RESOLVES),
            Map.entry("java.net.Inet6Address", RESOLVES),
            Map.entry("java.net.URLEncoder", Set.of()),
            Map.entry("java.net.URLDecoder", Set.of()),
            Map.entry("java.net.IDN", Set.of()),
            Map.entry("java.sql.Timestamp", Set.of()),
            Map.entry("java.sql.Date", Set.of()),
            Map.entry("java.sql.Time", Set.of()),
            Map.entry("java.sql.JDBCType", Set.of()),
            Map.entry("java.sql.Types", Set.of()),
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

    private JdkEffects() {
    }

    /**
     * Whether the class can be effectful at all: annotations and exceptions never are, they only carry data, and
     * neither are arrays, whose only methods copy them.
     */
    static boolean canHaveEffects(JavaClass javaClass) {
        return !javaClass.isArray() && !javaClass.isAnnotation() && !javaClass.isAssignableTo(Throwable.class);
    }

    /** Whether using this type at all means touching the outside world, like {@code HttpClient} or {@code Random}. */
    static boolean isImpureType(JavaClass type, List<PackageMatcher> extraImpurePackages) {
        String packageName = type.getPackageName();
        return canHaveEffects(type) && !EFFECTS_OF_VALUE_TYPES.containsKey(type.getName())
                && (IMPURE_PACKAGES.contains(packageName)
                        || IMPURE_TYPES.contains(type.getName())
                        || extraImpurePackages.stream().anyMatch(matcher -> matcher.matches(packageName)));
    }

    /** Whether calling the target uses an outside-world API: I/O, the clock, randomness, the environment, threads. */
    static boolean isImpure(CodeUnitAccessTarget target, List<PackageMatcher> extraImpurePackages) {
        JavaClass owner = target.getOwner();
        String name = owner.getName();
        if (!canHaveEffects(owner)) {
            return false;
        }
        if (EFFECTS_OF_VALUE_TYPES.containsKey(name)) {
            return EFFECTS_OF_VALUE_TYPES.get(name).contains(target.getName());
        }
        if (CONSTRUCTOR.equals(target.getName())
                && ("java.io".equals(owner.getPackageName()) || CLOCK_CONSTRUCTORS.contains(name))) {
            return touchesTheWorldOnCreation(target);
        }
        return isImpureType(owner, extraImpurePackages)
                || IMPURE_CALLS.contains(name + "." + target.getName())
                || "java.time".equals(owner.getPackageName()) && "now".equals(target.getName());
    }

    /**
     * Whether creating the stream or date touches the outside world: a stream that opens the file it is given by
     * name, as {@code new FileReader("prices.txt")} does, or reads or writes an object stream's header, or a date
     * made without arguments, which reads the clock. Wrapping a stream doesn't.
     */
    private static boolean touchesTheWorldOnCreation(CodeUnitAccessTarget constructor) {
        List<JavaClass> parameters = constructor.getRawParameterTypes();
        if (CLOCK_CONSTRUCTORS.contains(constructor.getOwner().getName())) {
            return parameters.isEmpty();
        }
        return HEADER_STREAMS.contains(constructor.getOwner().getName())
                || parameters.stream().findFirst()
                        .filter(first -> first.isEquivalentTo(String.class) || first.isEquivalentTo(java.io.File.class))
                        .isPresent();
    }
}
