package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.PackageMatcher;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which JDK APIs touch the outside world: I/O, the clock, randomness, the environment, threads, executors or processes,
 * plus the packages a project treats as impure. Holding a thread or an executor, or reading the current thread, isn't
 * an effect; starting, configuring or running work on one is. Using {@code Path}, {@code URI}, {@code File}, an
 * {@code InetAddress} or a {@code java.sql.Timestamp} as data is not I/O, and neither are encoding a URL, arrays,
 * the options enums of I/O packages or extending {@code SimpleFileVisitor}. A {@code java.io} stream touches the
 * outside world where it is opened: on a file name, as {@code new FileReader("prices.txt")} is, from a classpath
 * resource, or as an object stream, which reads or writes a header. Holding, wrapping, reading or writing a stream
 * someone else opened only processes its data, the way a {@code StringWriter} would.
 */
final class JdkEffects {

    private static final String CONSTRUCTOR = "<init>";
    /** Packages whose impure calls reach a database or another machine. */
    private static final Set<String> REMOTE_PACKAGES = Set.of("java.net", "java.net.http", "java.sql", "javax.sql",
            "java.rmi");
    private static final Set<String> IMPURE_PACKAGES =
            Set.of("java.nio.file", "java.nio.channels", "java.net", "java.net.http", "java.sql", "javax.sql");
    /** Types whose every use reads the clock or randomness. */
    private static final Set<String> TIME_AND_CHANCE_TYPES = Set.of("java.time.Clock", "java.time.InstantSource",
            "java.util.Random", "java.security.SecureRandom", "java.util.concurrent.ThreadLocalRandom",
            "java.util.random.RandomGenerator");
    private static final Set<String> IMPURE_TYPES = union(TIME_AND_CHANCE_TYPES,
            Set.of("java.lang.ProcessBuilder", "java.lang.Runtime", "java.lang.IO", "java.io.Console"));
    /** Calls that read the clock or randomness. */
    private static final Set<String> TIME_AND_CHANCE_CALLS = Set.of("java.lang.System.currentTimeMillis",
            "java.lang.System.nanoTime", "java.lang.Math.random", "java.util.UUID.randomUUID",
            "java.util.Calendar.getInstance");
    /** Calls that read how the process was started: environment variables, system properties, or its console. */
    private static final Set<String> CONFIGURATION_CALLS = Set.of("java.lang.System.getenv",
            "java.lang.System.getProperty", "java.lang.System.getProperties", "java.lang.Integer.getInteger",
            "java.lang.Long.getLong", "java.lang.Boolean.getBoolean", "java.lang.System.console");
    private static final Set<String> IMPURE_CALLS = union(union(TIME_AND_CHANCE_CALLS, CONFIGURATION_CALLS), Set.of(
            "java.lang.Class.getResourceAsStream", "java.lang.ClassLoader.getResourceAsStream",
            "java.lang.ClassLoader.getSystemResourceAsStream", "java.lang.Module.getResourceAsStream"));
    /** Types whose constructor without parameters reads the clock. */
    private static final Set<String> CLOCK_CONSTRUCTORS = Set.of("java.util.Date", "java.util.GregorianCalendar");
    /**
     * What an {@code InetAddress} does that resolves names or reaches the network. An {@code InetSocketAddress} resolves
     * its host when made from a name, and may look one up for {@code getHostName()}.
     */
    private static final Set<String> RESOLVES = Set.of("getByName", "getAllByName", "getLocalHost", "getHostName",
            "getCanonicalHostName", "isReachable");
    private static final Set<String> HEADER_STREAMS = Set.of("java.io.ObjectInputStream", "java.io.ObjectOutputStream");
    private static final String FILE = "java.io.File";
    /** What the first parameter of a {@code java.io} constructor that opens a file is: its name or its {@code File}. */
    private static final Set<String> FILE_NAMES = Set.of(String.class.getName(), FILE);
    /** Types outside {@code java.io} whose constructor opens a file, by the first parameter types that name it. */
    private static final Map<String, Set<String>> OPENING_CONSTRUCTORS = Map.of(
            "java.util.Scanner", Set.of(FILE, "java.nio.file.Path"),
            "java.util.zip.ZipFile", FILE_NAMES,
            "java.util.jar.JarFile", FILE_NAMES,
            "java.util.Formatter", FILE_NAMES);
    /** What binds a statement's parameters or reads a called procedure's results, rather than running it. */
    private static final Pattern JDBC_BINDING = Pattern.compile("(set|get)[A-Z].*|clearParameters|registerOutParameter|wasNull");
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
            Map.entry("java.nio.file.SimpleFileVisitor", Set.of()),
            Map.entry("java.nio.file.FileVisitor", Set.of()),
            Map.entry("java.net.URI", Set.of()),
            Map.entry("java.net.URL", Set.of("openConnection", "openStream", "getContent")),
            Map.entry(FILE, Set.of("exists", "isFile", "isDirectory", "isHidden", "canRead", "canWrite", "canExecute",
                    "length", "lastModified", "list", "listFiles", "listRoots", "delete", "deleteOnExit", "mkdir",
                    "mkdirs", "createNewFile", "createTempFile", "renameTo", "getCanonicalPath", "getCanonicalFile",
                    "setLastModified", "setReadOnly", "setReadable", "setWritable", "setExecutable", "getTotalSpace",
                    "getFreeSpace", "getUsableSpace")),
            Map.entry("java.net.InetAddress", RESOLVES),
            Map.entry("java.net.Inet4Address", RESOLVES),
            Map.entry("java.net.Inet6Address", RESOLVES),
            Map.entry("java.net.InetSocketAddress", Set.of(CONSTRUCTOR, "getHostName")),
            Map.entry("java.net.URLEncoder", Set.of()),
            Map.entry("java.net.URLDecoder", Set.of()),
            Map.entry("java.net.IDN", Set.of()),
            Map.entry("java.sql.Timestamp", Set.of()),
            Map.entry("java.sql.Date", Set.of()),
            Map.entry("java.sql.Time", Set.of()),
            Map.entry("java.sql.JDBCType", Set.of()),
            Map.entry("java.sql.Types", Set.of()),
            Map.entry("java.sql.ResultSetMetaData", Set.of()),
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

    /**
     * Whether calling the target only reads the clock or randomness, as {@code Instant.now()}, {@code new Date()} and
     * {@code UUID.randomUUID()} do, rather than doing I/O.
     */
    static boolean readsTimeOrChance(CodeUnitAccessTarget target) {
        String owner = target.getOwner().getName();
        return TIME_AND_CHANCE_TYPES.contains(owner) || TIME_AND_CHANCE_CALLS.contains(owner + "." + target.getName())
                || "java.time".equals(target.getOwner().getPackageName()) && "now".equals(target.getName())
                || CLOCK_CONSTRUCTORS.contains(owner) && CONSTRUCTOR.equals(target.getName());
    }

    /**
     * Whether calling the target only reads the process's state, which is cheap and can't fail: the clock, randomness,
     * environment variables, system properties or whether there is a console.
     */
    static boolean readsProcessState(CodeUnitAccessTarget target) {
        return readsTimeOrChance(target) || CONFIGURATION_CALLS.contains(target.getOwner().getName() + "." + target.getName());
    }

    /**
     * Whether calling the target reads the rows of a JDBC result, as {@code rows.getString("name")} does, or binds a
     * parameter of a statement, as {@code statement.setString(1, name)} does: on a result or statement a method is
     * given, the caller ran the query, and the method only maps or fills in data, as a row mapper or binder does.
     */
    static boolean readsOrBindsJdbcData(CodeUnitAccessTarget target) {
        JavaClass owner = target.getOwner();
        String name = target.getName();
        return owner.isAssignableTo("java.sql.ResultSet")
                || owner.isAssignableTo("java.sql.PreparedStatement") && JDBC_BINDING.matcher(name).matches();
    }

    /**
     * Whether the call on a clock or random type creates one rather than reading it, as {@code Clock.systemUTC()},
     * {@code new SecureRandom()}, {@code ThreadLocalRandom.current()} and {@code clock.withZone(zone)} do: reading the
     * time or a number is the effect.
     */
    private static boolean createsTimeOrChance(CodeUnitAccessTarget target) {
        return CONSTRUCTOR.equals(target.getName()) || TIME_AND_CHANCE_TYPES.contains(target.getRawReturnType().getName());
    }

    /** Whether the call's API reaches a database or another machine: JDBC, the network or RMI. */
    static boolean reachesRemote(CodeUnitAccessTarget target) {
        return REMOTE_PACKAGES.contains(target.getOwner().getPackageName());
    }

    /** Whether the type is a clock or a source of randomness. */
    static boolean isTimeOrChanceType(JavaClass type) {
        return TIME_AND_CHANCE_TYPES.contains(type.getName());
    }

    /**
     * Whether using this type at all means touching the outside world, like {@code HttpClient} or {@code Random}. An
     * enum of such a package, as {@code StandardOpenOption} is, only names options.
     */
    static boolean isImpureType(JavaClass type, List<PackageMatcher> extraImpurePackages) {
        String packageName = type.getPackageName();
        return canHaveEffects(type) && !type.isEnum() && !EFFECTS_OF_VALUE_TYPES.containsKey(type.getName())
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
        if (TIME_AND_CHANCE_TYPES.contains(name) && createsTimeOrChance(target)) {
            return false;
        }
        if (CONSTRUCTOR.equals(target.getName()) && ("java.io".equals(owner.getPackageName())
                || CLOCK_CONSTRUCTORS.contains(name) || OPENING_CONSTRUCTORS.containsKey(name))) {
            return touchesTheWorldOnCreation(target);
        }
        return isImpureType(owner, extraImpurePackages)
                || IMPURE_CALLS.contains(name + "." + target.getName())
                || "java.time".equals(owner.getPackageName()) && "now".equals(target.getName());
    }

    /**
     * Whether creating the stream or date touches the outside world: a stream or file that opens the file its first
     * parameter names, as {@code new FileReader("prices.txt")} or {@code new Scanner(file)} do, an object stream,
     * which reads or writes a header, or a date made without arguments, which reads the clock.
     */
    private static boolean touchesTheWorldOnCreation(CodeUnitAccessTarget constructor) {
        String owner = constructor.getOwner().getName();
        List<JavaClass> parameters = constructor.getRawParameterTypes();
        if (CLOCK_CONSTRUCTORS.contains(owner)) {
            return parameters.isEmpty();
        }
        Set<String> names = OPENING_CONSTRUCTORS.getOrDefault(owner, FILE_NAMES);
        return HEADER_STREAMS.contains(owner)
                || parameters.stream().findFirst().filter(first -> names.contains(first.getName())).isPresent();
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        return Stream.concat(first.stream(), second.stream()).collect(Collectors.toUnmodifiableSet());
    }
}
