package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which uses of threads and executors are effects, seen through a rule that depends on it. Runs once for each Java
 * version's class files: see {@link CompiledProject#releases()}.
 */
@ParameterizedClass(name = "class files for Java {0}")
@MethodSource("io.github.yoavgeva.designguard.CompiledProject#releases")
class ThreadEffectsTest {

    private static final String SHOP = "shop";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void runningWorkOnThreadsIsServiceWorkButHoldingAnExecutorOrReadingTheCurrentThreadIsNot() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Jobs", """
                        package shop;

                        public class Jobs {
                            private final java.util.concurrent.ExecutorService executor;
                            private int submitted;

                            public Jobs(java.util.concurrent.ExecutorService executor) {
                                this.executor = executor;
                            }

                            public void run(Runnable job) {
                                submitted++;
                                executor.execute(job);
                            }
                        }
                        """)
                .add("Daemons", """
                        package shop;

                        public class Daemons {
                            private int made;

                            public Thread daemon(Thread thread) {
                                made++;
                                thread.setDaemon(true);
                                return thread;
                            }
                        }
                        """)
                .add("Pools", """
                        package shop;

                        public class Pools {
                            private java.util.concurrent.ExecutorService executor;

                            public Pools executor(java.util.concurrent.ExecutorService chosen) {
                                executor = chosen;
                                return this;
                            }

                            public Jobs build() {
                                return new Jobs(executor);
                            }
                        }
                        """)
                .add("Tracer", """
                        package shop;

                        public class Tracer {
                            private int traced;

                            public String trace(String message) {
                                traced++;
                                return Thread.currentThread().getName() + ": " + message + traced;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Daemons is service-like, but its field 'made' isn't final",
                "Jobs is service-like, but its field 'submitted' isn't final");
    }
}
