package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FunctionalCoreRulesTest {

    @TempDir
    private Path directory;

    private DesignGuard guard;

    @BeforeEach
    void compile() {
        guard = new CompiledProject(directory)
                .add("Invoice", """
                        package shop;

                        import java.time.Clock;
                        import java.time.Instant;
                        import java.time.LocalDate;
                        import java.util.function.Supplier;

                        public class Invoice {
                            public LocalDate due() {
                                return LocalDate.now().plusDays(30);
                            }

                            public long stamp() {
                                return System.currentTimeMillis();
                            }

                            public LocalDate dueWith(Clock clock) {
                                return LocalDate.now(clock).plusDays(30);
                            }

                            public Supplier<Instant> timestamps() {
                                return Instant::now;
                            }
                        }
                        """)
                .add("Ids", """
                        package shop;

                        public class Ids {
                            public String next() {
                                return java.util.UUID.randomUUID().toString() + new java.util.Random().nextInt();
                            }
                        }
                        """)
                .add("Settings", """
                        package shop;

                        public class Settings {
                            public String region() {
                                return System.getenv("REGION");
                            }
                        }
                        """)
                .add("Jobs", """
                        package shop;

                        import java.util.concurrent.ExecutorService;
                        import java.util.concurrent.Executors;

                        public class Jobs {
                            public void run(Runnable job) {
                                new Thread(job).start();
                                ExecutorService executor = Executors.newFixedThreadPool(2);
                                executor.submit(job);
                                executor.shutdown();
                            }
                        }
                        """)
                .add("App", """
                        package shop;

                        public final class App {
                            public static void main(String[] args) {
                                System.out.println(System.getenv("REGION") + java.time.Instant.now());
                                new Thread(() -> { }).start();
                            }
                        }
                        """)
                .guard("shop");
    }

    @Test
    void clockMustBeInjected() {
        assertThat(guard.dependencies().clockIsInjected().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(10, "Invoice reads the system clock through LocalDate.now()"),
                        tuple(14, "Invoice reads the system clock through System.currentTimeMillis()"),
                        tuple(22, "Invoice reads the system clock through Instant.now()"));
        assertThat(guard.dependencies().clockIsInjected().evaluate().get(0).fix())
                .isEqualTo("inject a java.time.Clock and pass it in, e.g. LocalDate.now(clock)");
    }

    @Test
    void randomnessMustBeInjected() {
        assertThat(guard.dependencies().randomnessIsInjected().evaluate()).extracting(Violation::message)
                .containsExactly("Ids generates randomness through UUID.randomUUID()",
                        "Ids generates randomness through new Random()");
    }

    @Test
    void configurationMustBeInjected() {
        assertThat(guard.dependencies().configurationIsInjected().evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("inject-configuration");
            assertThat(violation.message()).isEqualTo("Settings reads the environment through System.getenv()");
            assertThat(violation.fix()).isEqualTo("read configuration once in wiring code and inject the values");
        });
    }

    @Test
    void threadsComeFromExecutors() {
        assertThat(guard.dependencies().noRawThreads().evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.message()).isEqualTo("Jobs starts a thread directly through new Thread()");
            assertThat(violation.fix()).isEqualTo("submit the work to an injected ExecutorService,"
                    + " e.g. one from Executors.newFixedThreadPool(n)");
        });
    }
}
