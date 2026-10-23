package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.io.TempDir;

/** Code only newer Java versions compile: their APIs, syntax and bytecode. Each test runs where the JDK can compile it. */
class NewerJavaCodeTest {

    private static final String SHOP = "shop";

    @TempDir
    private Path directory;

    @Test
    @EnabledForJreRange(minVersion = 21)
    void virtualThreadsAreRawThreadsToo() {
        var guard = new CompiledProject(directory).release(21)
                .add("Jobs", """
                        package shop;

                        public class Jobs {
                            public void run(Runnable job) {
                                Thread.ofVirtual().start(job);
                                Thread.startVirtualThread(job);
                                Thread.ofPlatform().name("worker").unstarted(job);
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().noRawThreads().evaluate()).extracting(Violation::message).containsExactly(
                "Jobs starts a thread directly through Thread.Builder.OfVirtual.start()",
                "Jobs starts a thread directly through Thread.startVirtualThread()",
                "Jobs starts a thread directly through Thread.Builder.OfPlatform.unstarted()");
    }

    @Test
    @EnabledForJreRange(minVersion = 21)
    void layersSeeTypesMatchedInASwitch() {
        var guard = new CompiledProject(directory).release(21)
                .add("Shape", "package shop.web;\n\npublic sealed interface Shape permits Circle, Square {\n}\n")
                .add("Circle", "package shop.web;\n\npublic record Circle(double radius) implements Shape {\n}\n")
                .add("Square", "package shop.web;\n\npublic record Square(double side) implements Shape {\n}\n")
                .add("Sizes", """
                        package shop.persistence;

                        public class Sizes {
                            public double size(Object shape) {
                                return switch (shape) {
                                    case shop.web.Circle circle -> circle.radius();
                                    case shop.web.Square(double side) -> side;
                                    default -> 0;
                                };
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.architecture().layered().layer("Web", "..web..").layer("Persistence", "..persistence..")
                .evaluate()).extracting(Violation::message).containsExactly(
                        "Sizes (layer Persistence) depends on Circle (layer Web), which Persistence may not use",
                        "Sizes (layer Persistence) depends on Square (layer Web), which Persistence may not use");
    }

    @Test
    @EnabledForJreRange(minVersion = 25)
    void instanceAndParameterlessMainMethodsAreWiringFromJava25() {
        var configured = ShopFixture.write(directory).release(25)
                .add("Launcher", """
                        package shop;

                        import shop.orders.OrderService;
                        import shop.payments.StripeClient;

                        class Launcher {
                            void main() {
                                IO.println(new OrderService(new StripeClient()));
                            }
                        }
                        """)
                .add("Tool", """
                        package shop;

                        import shop.payments.StripeClient;

                        class Tool {
                            static void main() {
                                IO.println(new StripeClient().charge(1));
                            }
                        }
                        """)
                .configure(SHOP);

        assertThat(configured.build().dependencies().injectServices().evaluate()).isEmpty();
        assertThat(configured.forJava(21).build().dependencies().injectServices().evaluate())
                .extracting(Violation::message).containsExactly(
                        "Launcher creates service-like OrderService with new",
                        "Launcher creates service-like StripeClient with new",
                        "Tool creates service-like StripeClient with new");
    }

    @Test
    @EnabledForJreRange(minVersion = 25)
    void consoleIoThroughJavaLangIoIsAnEffect() {
        var guard = new CompiledProject(directory).release(25)
                .add("Greeter", """
                        package shop;

                        public class Greeter {
                            public void greet(String name) {
                                IO.println("Hello " + name);
                            }
                        }
                        """)
                .add("Checkout", """
                        package shop;

                        public class Checkout {
                            public void done() {
                                new Greeter().greet("customer");
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().injectServices().evaluate()).extracting(Violation::message)
                .containsExactly("Checkout creates service-like Greeter with new");
    }

    @Test
    @EnabledForJreRange(minVersion = 25)
    void statementsBeforeSuperStillCountAsCreatingServices() {
        var guard = new CompiledProject(directory).release(25)
                .add("Meter", """
                        package shop;

                        public class Meter {
                            public long read() {
                                return System.nanoTime();
                            }
                        }
                        """)
                .add("Gateway", """
                        package shop;

                        public abstract class Gateway {
                            private final Meter meter;

                            protected Gateway(Meter meter) {
                                this.meter = meter;
                            }

                            public long elapsed() {
                                return meter.read();
                            }
                        }
                        """)
                .add("StripeGateway", """
                        package shop;

                        import module java.base;

                        public final class StripeGateway extends Gateway {
                            public StripeGateway(List<String> keys) {
                                if (keys.isEmpty()) {
                                    throw new IllegalArgumentException("no API keys");
                                }
                                super(new Meter());
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().injectServices().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(tuple(10, "StripeGateway creates service-like Meter with new"));
        assertThat(guard.guardrails().noStubs().evaluate()).isEmpty();
    }
}
