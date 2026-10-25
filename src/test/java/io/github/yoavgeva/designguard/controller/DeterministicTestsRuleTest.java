package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeterministicTestsRuleTest {

    private static final String SHOP = "shop";
    private static final String CART = "Cart";
    private static final String CART_SOURCE =
            "package shop;\n\npublic class Cart {\n    public int total() {\n        return 3;\n    }\n}\n";

    @TempDir
    private Path directory;

    @Test
    void testsUseFixedTimeDataAndSettings() {
        DesignGuard guard = new CompiledProject(directory)
                .add(CART, CART_SOURCE)
                .addTest("CartTest", """
                        package shop;

                        import java.time.Clock;
                        import java.time.Instant;
                        import java.time.LocalDate;
                        import java.time.ZoneOffset;
                        import java.util.Random;

                        class CartTest {
                            private final long seed = System.currentTimeMillis();

                            @org.junit.jupiter.api.Test
                            void totals() throws InterruptedException {
                                LocalDate today = LocalDate.now();
                                int discount = new Random().nextInt(10);
                                String region = System.getenv("REGION");
                                Thread.sleep(100);
                                org.assertj.core.api.Assertions.assertThat(new Cart().total() + discount + today.getDayOfMonth() + region).isNotEmpty();
                            }

                            @org.junit.jupiter.api.Test
                            void totalsOnAFixedDay() {
                                Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
                                LocalDate day = LocalDate.now(clock);
                                int discount = new Random(42).nextInt(10);
                                org.assertj.core.api.Assertions.assertThat(day).isEqualTo(LocalDate.of(2026, 1, 1));
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.tests().testsUseAFixedClock().evaluate()).extracting(Violation::rule, Violation::line,
                Violation::message).containsExactly(
                        tuple("tests-fixed-clock", 10, "CartTest reads the system clock through System.currentTimeMillis()"),
                        tuple("tests-fixed-clock", 14, "test CartTest.totals() reads the system clock through LocalDate.now()"));
        assertThat(guard.tests().testsUseSeededRandomness().evaluate()).extracting(Violation::rule, Violation::line,
                Violation::message).containsExactly(tuple("tests-seeded-randomness", 15,
                        "test CartTest.totals() uses unseeded randomness through new Random()"));
        assertThat(guard.tests().testsDontReadTheEnvironment().evaluate()).extracting(Violation::rule, Violation::line,
                Violation::message).containsExactly(tuple("tests-no-environment", 16,
                        "test CartTest.totals() reads the environment through System.getenv()"));
        assertThat(guard.tests().testsDontSleep().evaluate()).extracting(Violation::rule, Violation::line,
                Violation::message).containsExactly(tuple("tests-no-sleep", 17,
                        "test CartTest.totals() sleeps through Thread.sleep()"));
        assertThat(Stream.of(guard.tests().testsUseAFixedClock(), guard.tests().testsUseSeededRandomness(),
                        guard.tests().testsDontReadTheEnvironment(), guard.tests().testsDontSleep())
                .map(rule -> rule.evaluate().get(0).fix())).containsExactly(
                "use a fixed time, such as Instant.parse(...), or pass a fixed Clock, such as Clock.fixed(...),"
                        + " to the code under test",
                "use fixed data, or a Random with a fixed seed",
                "pass the value in, so the test doesn't depend on the machine it runs on",
                "wait for the condition instead, for example with Awaitility, or make time injectable");
    }

    @Test
    void onlyCodeTheTestsRunIsTestCode() {
        DesignGuard guard = new CompiledProject(directory)
                .add(CART, CART_SOURCE)
                .addTest("CartSamples", """
                        package shop;

                        final class CartSamples {
                            static String region() {
                                return System.getenv("REGION");
                            }
                        }
                        """)
                .addTest("CartTest", """
                        package shop;

                        class CartTest {
                            @org.junit.jupiter.api.Test
                            void totals() {
                                org.assertj.core.api.Assertions.assertThat(CartSamples.region() + new Cart().total()).isNotEmpty();
                            }
                        }
                        """)
                .addTest("CartSimulation", """
                        package shop.load;

                        public class CartSimulation {
                            String baseUrl = System.getProperty("baseURL", "http://localhost:8080");
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.tests().testsDontReadTheEnvironment().evaluate()).extracting(Violation::message)
                .containsExactly("CartSamples.region() reads the environment through System.getenv()");
    }

    @Test
    void clockReadingsOverwrittenBeforeUseAreFixed() {
        DesignGuard guard = new CompiledProject(directory)
                .add(CART, CART_SOURCE)
                .addTest("CartTest", """
                        package shop;

                        import java.util.Calendar;
                        import java.util.Date;
                        import java.util.GregorianCalendar;

                        class CartTest {
                            @org.junit.jupiter.api.Test
                            void datesAreFixed() {
                                Calendar day = Calendar.getInstance();
                                day.clear();
                                day.set(2026, Calendar.JANUARY, 1);
                                Calendar epoch;
                                epoch = Calendar.getInstance();
                                epoch.setTimeInMillis(0);
                                Date start = new Date();
                                start.setTime(0);
                                Calendar copy = new GregorianCalendar();
                                copy.setTime(start);
                                org.assertj.core.api.Assertions.assertThat(day.before(epoch) || copy.after(day)).isFalse();
                            }

                            @org.junit.jupiter.api.Test
                            void datesKeepTheTimeOfDay() {
                                Calendar day = Calendar.getInstance();
                                day.set(2026, Calendar.JANUARY, 1);
                                Calendar later = new GregorianCalendar();
                                org.assertj.core.api.Assertions.assertThat(day.before(later)).isTrue();
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.tests().testsUseAFixedClock().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(25, "test CartTest.datesKeepTheTimeOfDay() reads the system clock through Calendar.getInstance()"),
                        tuple(27, "test CartTest.datesKeepTheTimeOfDay() reads the system clock through new GregorianCalendar()"));
    }

    @Test
    void propertiesSavedToRestoreAreTheTestsOwn() {
        DesignGuard guard = new CompiledProject(directory)
                .add("Paths", "package shop;\n\npublic class Paths {\n    public String home() {\n        return System.getProperty(\"user.home\");\n    }\n}\n")
                .addTest("PathsTest", """
                        package shop;

                        import org.junit.jupiter.api.AfterEach;
                        import org.junit.jupiter.api.BeforeEach;
                        import org.junit.jupiter.api.Test;

                        class PathsTest {
                            private String savedTmp;

                            @BeforeEach
                            void useATempDirectory() {
                                savedTmp = System.getProperty("java.io.tmpdir");
                                System.setProperty("java.io.tmpdir", "/work/tmp");
                            }

                            @AfterEach
                            void restoreTheTempDirectory() {
                                System.setProperty("java.io.tmpdir", savedTmp);
                            }

                            @Test
                            void homeIsTheUsersHome() {
                                String oldHome = System.getProperty("user.home");
                                try {
                                    System.setProperty("user.home", "/home/ada");
                                    org.assertj.core.api.Assertions.assertThat(new Paths().home()).isEqualTo("/home/ada");
                                } finally {
                                    if (oldHome == null) {
                                        System.clearProperty("user.home");
                                    } else {
                                        System.setProperty("user.home", oldHome);
                                    }
                                }
                            }

                            @Test
                            void printsOnePerLine() {
                                String lf = System.getProperty("line.separator");
                                String key = "user.dir";
                                String java = System.getProperty("java.home") + "/bin/java " + System.getProperty("java.class.path");
                                org.assertj.core.api.Assertions.assertThat(new Paths().home() + lf + System.getProperty(key) + java)
                                        .contains(lf);
                            }

                            @Test
                            void opensOnWindows() {
                                org.assertj.core.api.Assertions.assertThat(System.getProperty("os.name").contains("Windows")
                                        || new Paths().home().isEmpty()).isTrue();
                            }

                            @Test
                            void homeIsUnderTheMachinesHome() {
                                String home = System.getProperty("user.home");
                                System.setProperty("user.home", home + "/ada");
                                org.assertj.core.api.Assertions.assertThat(new Paths().home()).endsWith("/ada");
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.tests().testsDontReadTheEnvironment().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(47, "test PathsTest.opensOnWindows() reads the environment through System.getProperty()"),
                        tuple(53, "test PathsTest.homeIsUnderTheMachinesHome() reads the environment through System.getProperty()"));
    }

    @Test
    void readingsATestOnlyPrintsOrIgnoresDontDecideItsOutcome() {
        DesignGuard guard = new CompiledProject(directory)
                .add(CART, CART_SOURCE)
                .addTest("CartTimingTest", """
                        package shop;

                        import java.util.Arrays;
                        import java.util.Random;
                        import java.util.concurrent.TimeUnit;
                        import java.util.concurrent.atomic.AtomicLongArray;
                        import java.util.logging.Logger;
                        import org.junit.jupiter.api.Test;

                        class CartTimingTest {
                            private static final Logger LOG = Logger.getLogger("timing");
                            private final Cart cart = new Cart();

                            @Test
                            void printsHowLongTotalsTake() {
                                long t1 = System.currentTimeMillis();
                                cart.total();
                                long t2 = System.currentTimeMillis();
                                long took = (t2 - t1) / 10;
                                System.out.printf("totals took %d ms on %s%n", took, System.getProperty("os.name"));
                                t1 = System.nanoTime();
                                cart.total();
                                LOG.info("again: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1));
                            }

                            @Test
                            void benchmarksTotals() {
                                long[] times = new long[5];
                                for (int i = 0; i < times.length; i++) {
                                    long start = System.nanoTime();
                                    cart.total();
                                    times[i] = System.nanoTime() - start;
                                }
                                printStats(times);
                                long ignored = System.currentTimeMillis();
                                org.assertj.core.api.Assertions.assertThat(cart.total()).isEqualTo(3);
                            }

                            @Test
                            void measuresTotals() {
                                AtomicLongArray elapsed = measure();
                                org.assertj.core.api.Assertions.assertThat(cart.total()).isEqualTo(3);
                            }

                            @Test
                            void totalsQuickly() {
                                long start = System.nanoTime();
                                cart.total();
                                org.assertj.core.api.Assertions.assertThat(System.nanoTime() - start).isLessThan(1_000_000L);
                            }

                            @Test
                            void totalsWithASeed() {
                                long seed = System.currentTimeMillis();
                                org.assertj.core.api.Assertions.assertThat(new Random(seed).nextInt(1) + cart.total()).isEqualTo(3);
                            }

                            @Test
                            void totalsForTheUser() {
                                String user = System.getProperty("user.name");
                                org.assertj.core.api.Assertions.assertThat(user.length() + cart.total()).isPositive();
                            }

                            @Test
                            void totalsForRandomNames() {
                                byte[] bytes = new byte[4];
                                new Random().nextBytes(bytes);
                                org.assertj.core.api.Assertions.assertThat(new String(bytes) + cart.total()).isNotEmpty();
                            }

                            @Test
                            void totalsForAHome() {
                                java.util.function.Supplier<String> home = () -> System.getProperty("user.home");
                                org.assertj.core.api.Assertions.assertThat(home.get() + cart.total()).isNotEmpty();
                            }

                            @Test
                            void recordsTimesByCart() {
                                java.util.Map<String, java.util.List<Long>> byCart = new java.util.HashMap<>();
                                java.util.List<Long> times = byCart.computeIfAbsent("cart", key -> new java.util.ArrayList<>());
                                times.add(System.currentTimeMillis());
                                org.assertj.core.api.Assertions.assertThat(byCart.get("cart").get(0) + cart.total()).isPositive();
                            }

                            @Test
                            void totalsOnTheLastDay() {
                                java.util.Calendar day = java.util.Calendar.getInstance();
                                day.set(2026, java.util.Calendar.DECEMBER, 31);
                                org.assertj.core.api.Assertions.assertThat(cart.total()).isEqualTo(3);
                            }

                            private AtomicLongArray measure() {
                                AtomicLongArray total = new AtomicLongArray(1);
                                Runnable run = () -> {
                                    long before = System.currentTimeMillis();
                                    cart.total();
                                    total.addAndGet(0, System.currentTimeMillis() - before);
                                };
                                run.run();
                                return total;
                            }

                            private void printStats(long[] timesNanos) {
                                Arrays.sort(timesNanos);
                                long sum = 0;
                                for (long time : timesNanos) {
                                    sum += time;
                                }
                                System.out.println("median " + millis(timesNanos[timesNanos.length / 2])
                                        + " average " + millis((double) sum / timesNanos.length));
                            }

                            private double millis(double nanos) {
                                return nanos / 1_000_000.0;
                            }

                            private double millis(long nanos) {
                                return nanos / 1_000_000.0;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(Stream.of(guard.tests().testsUseAFixedClock(), guard.tests().testsDontReadTheEnvironment(),
                        guard.tests().testsUseSeededRandomness())
                .flatMap(rule -> rule.evaluate().stream()))
                .extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(47, "test CartTimingTest.totalsQuickly() reads the system clock through System.nanoTime()"),
                        tuple(49, "test CartTimingTest.totalsQuickly() reads the system clock through System.nanoTime()"),
                        tuple(54, "test CartTimingTest.totalsWithASeed() reads the system clock through System.currentTimeMillis()"),
                        tuple(81, "test CartTimingTest.recordsTimesByCart() reads the system clock through System.currentTimeMillis()"),
                        tuple(60, "test CartTimingTest.totalsForTheUser() reads the environment through System.getProperty()"),
                        tuple(73, "test CartTimingTest.totalsForAHome() reads the environment through System.getProperty()"),
                        tuple(67, "test CartTimingTest.totalsForRandomNames() uses unseeded randomness through new Random()"));
    }
}
