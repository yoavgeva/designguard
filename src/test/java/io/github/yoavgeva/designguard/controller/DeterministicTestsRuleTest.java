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

    @TempDir
    private Path directory;

    @Test
    void testsUseFixedTimeDataAndSettings() {
        DesignGuard guard = new CompiledProject(directory)
                .add("Cart", "package shop;\n\npublic class Cart {\n    public int total() {\n        return 3;\n    }\n}\n")
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
                                org.assertj.core.api.Assertions.assertThat(new Cart().total() + discount).isPositive();
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
                .guard("shop");

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
                "pass a fixed Clock, such as Clock.fixed(...), to the code under test",
                "use fixed data, or a Random with a fixed seed",
                "pass the value in, so the test doesn't depend on the machine it runs on",
                "wait for the condition instead, for example with Awaitility, or make time injectable");
    }

    @Test
    void clockReadingsOverwrittenBeforeUseAreFixed() {
        DesignGuard guard = new CompiledProject(directory)
                .add("Cart", "package shop;\n\npublic class Cart {\n    public int total() {\n        return 3;\n    }\n}\n")
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
                .guard("shop");

        assertThat(guard.tests().testsUseAFixedClock().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(25, "test CartTest.datesKeepTheTimeOfDay() reads the system clock through Calendar.getInstance()"),
                        tuple(27, "test CartTest.datesKeepTheTimeOfDay() reads the system clock through new GregorianCalendar()"));
    }
}
