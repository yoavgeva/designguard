package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds test code that gives different results on different runs or machines, one subject per check: it reads the
 * system clock, uses unseeded randomness, reads environment variables or system properties, or sleeps. Seeded
 * {@code new Random(42)}, {@code Clock.fixed(...)} and {@code now(clock)} are fine, and so are a clock reading the
 * test overwrites before using it, see {@link OverwrittenTimes}, a system property it saves to restore, see
 * {@link SavedProperties}, where the JVM and its directories are, see {@link JvmLocations}, and a value it only
 * prints, logs or ignores, see {@link ReportedReadings}. Test methods, helpers and field initializers are all checked,
 * in the code tests run ({@link CodeTestsRun}); fixture code inside strings isn't code.
 */
public final class NondeterministicTestsCheck {

    private static final Set<String> TIME_TYPES = Set.of("Instant", "LocalDate", "LocalDateTime", "LocalTime",
            "ZonedDateTime", "OffsetDateTime", "OffsetTime", "Year", "YearMonth", "MonthDay");
    private static final Set<String> TIME_UNITS = Set.of("NANOSECONDS", "MICROSECONDS", "MILLISECONDS", "SECONDS",
            "MINUTES", "HOURS", "DAYS");
    private static final String SYSTEM = "System";
    private static final List<Call> CALLS = List.of(
            new Call(TIME_TYPES, "now", Arguments.NONE, Kind.CLOCK),
            new Call(Set.of(SYSTEM), "currentTimeMillis", Arguments.ANY, Kind.CLOCK),
            new Call(Set.of(SYSTEM), "nanoTime", Arguments.ANY, Kind.CLOCK),
            new Call(Set.of("Clock"), "systemUTC", Arguments.ANY, Kind.CLOCK),
            new Call(Set.of("Clock"), "systemDefaultZone", Arguments.ANY, Kind.CLOCK),
            new Call(Set.of("Calendar"), "getInstance", Arguments.ANY, Kind.CLOCK),
            new Call(Set.of("Math"), "random", Arguments.ANY, Kind.RANDOMNESS),
            new Call(Set.of("UUID"), "randomUUID", Arguments.ANY, Kind.RANDOMNESS),
            new Call(Set.of("ThreadLocalRandom"), "current", Arguments.ANY, Kind.RANDOMNESS),
            new Call(Set.of(SYSTEM), "getenv", Arguments.ANY, Kind.ENVIRONMENT),
            new Call(Set.of(SYSTEM), "getProperty", Arguments.ANY, Kind.ENVIRONMENT),
            new Call(Set.of("Thread"), "sleep", Arguments.ANY, Kind.SLEEP),
            new Call(TIME_UNITS, "sleep", Arguments.ANY, Kind.SLEEP));
    private static final List<Call> CREATIONS = List.of(
            new Call(Set.of(), "Random", Arguments.NONE, Kind.RANDOMNESS),
            new Call(Set.of(), "SecureRandom", Arguments.NONE, Kind.RANDOMNESS),
            new Call(Set.of(), "Date", Arguments.NONE, Kind.CLOCK),
            new Call(Set.of(), "GregorianCalendar", Arguments.NONE, Kind.CLOCK));

    private final Kind kind;

    private NondeterministicTestsCheck(Kind kind) {
        this.kind = kind;
    }

    /**
     * Forbids reading the system clock: {@code now()} without a {@code Clock}, {@code System.currentTimeMillis()},
     * {@code nanoTime()}, {@code new Date()}, {@code new GregorianCalendar()}, {@code Calendar.getInstance()} and the
     * system {@code Clock}s.
     *
     * @return the check
     */
    public static NondeterministicTestsCheck clock() {
        return new NondeterministicTestsCheck(Kind.CLOCK);
    }

    /**
     * Forbids unseeded randomness: {@code new Random()}, {@code new SecureRandom()}, {@code Math.random()},
     * {@code UUID.randomUUID()} and {@code ThreadLocalRandom.current()}.
     *
     * @return the check
     */
    public static NondeterministicTestsCheck randomness() {
        return new NondeterministicTestsCheck(Kind.RANDOMNESS);
    }

    /**
     * Forbids reading environment variables and system properties.
     *
     * @return the check
     */
    public static NondeterministicTestsCheck environment() {
        return new NondeterministicTestsCheck(Kind.ENVIRONMENT);
    }

    /**
     * Forbids sleeping: {@code Thread.sleep} and {@code TimeUnit.X.sleep}.
     *
     * @return the check
     */
    public static NondeterministicTestsCheck sleep() {
        return new NondeterministicTestsCheck(Kind.SLEEP);
    }

    /**
     * Finds violations in the test sources the filter selects.
     *
     * @param sources the parsed test sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        CodeTestsRun run = CodeTestsRun.in(sources);
        return sources.files().stream()
                .filter(file -> file.unit().getTypes().stream().anyMatch(run::isRun))
                .flatMap(file -> Stream.concat(
                        file.unit().findAll(MethodCallExpr.class).stream().flatMap(call -> called(call).stream()),
                        file.unit().findAll(ObjectCreationExpr.class).stream().flatMap(creation -> created(creation).stream()))
                        .filter(found -> found.kind() == kind && Scopes.selected(filter, file, found.node())
                                && !(kind == Kind.CLOCK && OverwrittenTimes.isOverwritten(found.node()))
                                && !(kind == Kind.ENVIRONMENT && (SavedProperties.isSaved(found.node())
                                        || JvmLocations.isRead(found.node())))
                                && !(kind != Kind.SLEEP && ReportedReadings.isOnlyReported(found.node())))
                        .map(found -> found.violation(file)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Optional<Found> called(MethodCallExpr call) {
        String scope = call.getScope().flatMap(NondeterministicTestsCheck::lastName).orElse("");
        return CALLS.stream()
                .filter(known -> known.scopes().contains(scope) && known.name().equals(call.getNameAsString())
                        && known.arguments().allows(call.getArguments().size()))
                .findFirst()
                .map(known -> new Found(call, call.getScope().map(Expression::toString).orElse("") + "."
                        + call.getNameAsString() + "()", known.kind()));
    }

    private static Optional<Found> created(ObjectCreationExpr creation) {
        String type = creation.getType().getNameAsString();
        return CREATIONS.stream()
                .filter(known -> known.name().equals(type) && known.arguments().allows(creation.getArguments().size()))
                .findFirst()
                .map(known -> new Found(creation, "new " + type + "()", known.kind()));
    }

    /** The last name in a scope such as {@code java.time.Instant} or {@code TimeUnit.SECONDS}. */
    private static Optional<String> lastName(Expression scope) {
        if (scope instanceof NodeWithSimpleName<?> named) {
            return Optional.of(named.getNameAsString());
        }
        return Optional.empty();
    }

    /** What a nondeterministic call does, and how to make the test fixed. */
    private enum Kind {
        CLOCK("tests-fixed-clock", "reads the system clock",
                "use a fixed time, such as Instant.parse(...), or pass a fixed Clock, such as Clock.fixed(...), to the"
                        + " code under test"),
        RANDOMNESS("tests-seeded-randomness", "uses unseeded randomness", "use fixed data, or a Random with a fixed seed"),
        ENVIRONMENT("tests-no-environment", "reads the environment",
                "pass the value in, so the test doesn't depend on the machine it runs on"),
        SLEEP("tests-no-sleep", "sleeps", "wait for the condition instead, for example with Awaitility, or make time injectable");

        private final String rule;
        private final String verb;
        private final String fix;

        Kind(String rule, String verb, String fix) {
            this.rule = rule;
            this.verb = verb;
            this.fix = fix;
        }
    }

    /** Which argument counts make a known call nondeterministic: {@code new Random(42)} is seeded. */
    private enum Arguments {
        NONE, ANY;

        boolean allows(int count) {
            return this == ANY || count == 0;
        }
    }

    /** A known nondeterministic call or creation: on any of these scopes, by name, with these arguments. */
    private record Call(Set<String> scopes, String name, Arguments arguments, Kind kind) {

        Call {
            scopes = Set.copyOf(scopes);
        }
    }

    /** A nondeterministic call found in a test file, described as written. */
    private record Found(Node node, String described, Kind kind) {

        Violation violation(ParsedFile file) {
            String where = Scopes.enclosingCallable(node)
                    .map(callable -> callable instanceof MethodDeclaration method && UnitTestMethods.isTest(method)
                            ? "test " + Scopes.describe(callable) : Scopes.describe(callable))
                    .orElseGet(() -> Scopes.enclosingType(node).map(type -> type.getNameAsString()).orElse("a test"));
            return Scopes.violation(kind.rule, file, node, where + " " + kind.verb + " through " + described, kind.fix);
        }
    }
}
