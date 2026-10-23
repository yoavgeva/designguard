package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import io.github.yoavgeva.designguard.model.JavaFeature;
import java.util.List;
import java.util.Set;

/**
 * The outside-world APIs a functional core shouldn't touch directly: the system clock, randomness, the environment
 * and raw threads. Each check forbids them outside wiring code, so the values can be injected and tests are
 * deterministic.
 */
public final class FunctionalCoreChecks {

    private static final Set<String> TIME_TYPES = Set.of("java.time.Instant", "java.time.LocalDate",
            "java.time.LocalDateTime", "java.time.LocalTime", "java.time.ZonedDateTime", "java.time.OffsetDateTime",
            "java.time.OffsetTime", "java.time.Year", "java.time.YearMonth", "java.time.MonthDay");
    private static final String SYSTEM = "java.lang.System";
    private static final String INJECT_RANDOM = "inject a java.util.random.RandomGenerator";

    private FunctionalCoreChecks() {
    }

    /**
     * Forbids reading the system clock: {@code now()} without a {@code Clock}, {@code System.currentTimeMillis()},
     * {@code new Date()}, {@code Calendar.getInstance()} and the system {@code Clock} factories.
     *
     * @return the check
     */
    public static ForbiddenCallsCheck clock() {
        return new ForbiddenCallsCheck("inject-clock", "reads the system clock", List.of(
                new ForbiddenCall(target -> TIME_TYPES.contains(target.getOwner().getName())
                        && "now".equals(target.getName())
                        && target.getRawParameterTypes().stream().noneMatch(FunctionalCoreChecks::isClock),
                        target -> "inject a java.time.Clock and pass it in, e.g. "
                                + target.getOwner().getSimpleName() + ".now(clock)"),
                ForbiddenCall.of(SYSTEM, Set.of("currentTimeMillis"), "inject a java.time.Clock and call clock.millis()"),
                ForbiddenCall.of("java.time.Clock", Set.of("systemUTC", "systemDefaultZone", "system"),
                        "inject the Clock instead of creating it here"),
                ForbiddenCall.withoutParameters("java.util.Date", ForbiddenCall.CONSTRUCTOR,
                        "inject a java.time.Clock and use Date.from(clock.instant())"),
                ForbiddenCall.of("java.util.Calendar", Set.of("getInstance"),
                        "inject a java.time.Clock and use java.time types")));
    }

    /**
     * Forbids creating randomness: {@code new Random()}, {@code new SecureRandom()}, {@code Math.random()},
     * {@code ThreadLocalRandom.current()}, {@code RandomGenerator.getDefault()} and {@code UUID.randomUUID()}.
     *
     * @return the check
     */
    public static ForbiddenCallsCheck randomness() {
        return new ForbiddenCallsCheck("inject-randomness", "generates randomness", List.of(
                ForbiddenCall.of("java.util.Random", Set.of(ForbiddenCall.CONSTRUCTOR), INJECT_RANDOM),
                ForbiddenCall.of("java.security.SecureRandom", Set.of(ForbiddenCall.CONSTRUCTOR), INJECT_RANDOM),
                ForbiddenCall.of("java.lang.Math", Set.of("random"), INJECT_RANDOM),
                ForbiddenCall.of("java.util.concurrent.ThreadLocalRandom", Set.of("current"), INJECT_RANDOM),
                ForbiddenCall.of("java.util.random.RandomGenerator", Set.of("getDefault", "of"), INJECT_RANDOM),
                ForbiddenCall.of("java.util.UUID", Set.of("randomUUID"),
                        "inject an ID supplier, e.g. a Supplier<UUID>, and call it")));
    }

    /**
     * Forbids reading configuration ad hoc: environment variables and system properties.
     *
     * @return the check
     */
    public static ForbiddenCallsCheck configuration() {
        String fix = "read configuration once in wiring code and inject the values";
        return new ForbiddenCallsCheck("inject-configuration", "reads the environment", List.of(
                ForbiddenCall.of(SYSTEM, Set.of("getenv", "getProperty", "getProperties"), fix),
                ForbiddenCall.of("java.lang.Integer", Set.of("getInteger"), fix),
                ForbiddenCall.of("java.lang.Long", Set.of("getLong"), fix),
                ForbiddenCall.of("java.lang.Boolean", Set.of("getBoolean"), fix)));
    }

    /**
     * Forbids starting threads by hand: {@code new Thread(...)}, {@code Thread.startVirtualThread(...)} and
     * {@code Thread.Builder.start/unstarted}.
     *
     * @param javaRelease the Java version the code targets: virtual threads are suggested from Java 21
     * @return the check
     */
    public static ForbiddenCallsCheck rawThreads(int javaRelease) {
        String executor = JavaFeature.VIRTUAL_THREADS.isAvailableIn(javaRelease)
                ? "Executors.newVirtualThreadPerTaskExecutor()" : "Executors.newFixedThreadPool(n)";
        String fix = "submit the work to an injected ExecutorService, e.g. one from " + executor;
        Set<String> builders = Set.of("java.lang.Thread$Builder", "java.lang.Thread$Builder$OfPlatform",
                "java.lang.Thread$Builder$OfVirtual");
        return new ForbiddenCallsCheck("no-raw-threads", "starts a thread directly", List.of(
                ForbiddenCall.of("java.lang.Thread", Set.of(ForbiddenCall.CONSTRUCTOR, "startVirtualThread"), fix),
                new ForbiddenCall(target -> builders.contains(target.getOwner().getName())
                        && Set.of("start", "unstarted").contains(target.getName()), target -> fix)));
    }

    private static boolean isClock(JavaClass parameterType) {
        return parameterType.isAssignableTo("java.time.InstantSource");
    }
}
