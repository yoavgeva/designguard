package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs once for each Java version's class files: see {@link CompiledProject#releases()}. */
@ParameterizedClass(name = "class files for Java {0}")
@MethodSource("io.github.yoavgeva.designguard.CompiledProject#releases")
class InjectServicesRuleTest {

    private static final String SHOP = "shop";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void flagsServicesCreatedOutsideWiringCode() {
        var guard = ShopFixture.write(directory).release(release)
                .add("Checkout", """
                        package shop.orders;

                        import shop.payments.StripeClient;

                        public class Checkout {
                            public String run(Money money) {
                                return new StripeClient().charge(money.cents()) + new Money(1);
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().injectServices().evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("inject-services");
            assertThat(violation.file()).endsWith("shop/orders/Checkout.java");
            assertThat(violation.line()).isEqualTo(7);
            assertThat(violation.message()).isEqualTo("Checkout creates service-like StripeClient with new");
            assertThat(violation.fix())
                    .isEqualTo("take StripeClient as a constructor parameter, and create it only in wiring code");
        });
    }

    @Test
    void flagsStaticSingletonAccess() {
        var guard = ShopFixture.write(directory).release(release)
                .add("Registry", """
                        package shop.orders;

                        public final class Registry {
                            private static final Registry INSTANCE = new Registry();
                            private final java.util.Random random = new java.util.Random();

                            public static Registry getInstance() {
                                return INSTANCE;
                            }

                            public int next() {
                                return random.nextInt();
                            }
                        }
                        """)
                .add("Lottery", """
                        package shop.orders;

                        public class Lottery {
                            public int draw() {
                                return Registry.getInstance().next();
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().injectServices().evaluate()).extracting(Violation::message).containsExactly(
                "Lottery reaches service-like Registry through the static Registry.getInstance()");
    }

    @Test
    void flagsServicesLookedUpInsteadOfInjected() {
        var guard = new CompiledProject(directory).release(release)
                .add("ApplicationContext", "package org.springframework.context;\n\npublic interface ApplicationContext {\n"
                        + "    <T> T getBean(Class<T> type);\n}\n")
                .add("Mailer", "package shop;\n\npublic interface Mailer {\n    void send(String to);\n}\n")
                .add("Checkout", """
                        package shop;

                        public class Checkout {
                            private final org.springframework.context.ApplicationContext context;

                            public Checkout(org.springframework.context.ApplicationContext context) {
                                this.context = context;
                            }

                            public void pay() {
                                context.getBean(Mailer.class).send("ada@example.com");
                                java.util.ServiceLoader.load(Mailer.class).findFirst().ifPresent(mailer -> mailer.send("x"));
                            }
                        }
                        """)
                .add("App", """
                        package shop;

                        public final class App {
                            public static void main(String[] args) {
                                java.util.ServiceLoader.load(Mailer.class).forEach(mailer -> mailer.send("started"));
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().injectServices().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(11, "Checkout looks up a service through ApplicationContext.getBean()"),
                        tuple(12, "Checkout looks up a service through ServiceLoader.load()"));
        assertThat(guard.dependencies().injectServices().evaluate().get(0).fix())
                .isEqualTo("take the service as a constructor parameter instead of looking it up");
    }

    @Test
    void creatingPureObjectsAndCallingFactoriesIsFine() {
        var guard = new CompiledProject(directory).release(release)
                .add("Sources", """
                        package shop;

                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.List;

                        public final class Sources {
                            private final List<String> lines;

                            private Sources(List<String> lines) {
                                this.lines = lines;
                            }

                            public static Sources read(Path file) throws java.io.IOException {
                                return new Sources(Files.readAllLines(file));
                            }

                            public List<String> lines() {
                                return lines;
                            }
                        }
                        """)
                .add("Checker", """
                        package shop;

                        public class Checker {
                            public int count(Sources sources) {
                                return sources.lines().size();
                            }
                        }
                        """)
                .add("Runner", """
                        package shop;

                        public class Runner {
                            public int run(java.nio.file.Path file) throws java.io.IOException {
                                return new Checker().count(Sources.read(file));
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().injectServices().evaluate()).isEmpty();
    }

    @Test
    void creatingObjectsFromInjectedAbstractionsIsFine() {
        var guard = new CompiledProject(directory).release(release)
                .add("Storage", "package shop;\n\npublic interface Storage {\n    void save(String report);\n}\n")
                .add("DiskStorage", """
                        package shop;

                        public final class DiskStorage implements Storage {
                            @Override
                            public void save(String report) {
                                try {
                                    java.nio.file.Files.writeString(java.nio.file.Path.of("report.txt"), report);
                                } catch (java.io.IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            }
                        }
                        """)
                .add("ScheduledReport", """
                        package shop;

                        public final class ScheduledReport {
                            private final Storage storage;

                            public ScheduledReport(Storage storage) {
                                this.storage = storage;
                            }

                            public void run() {
                                storage.save("daily");
                            }
                        }
                        """)
                .add("Reports", """
                        package shop;

                        public final class Reports {
                            private final Storage storage;

                            public Reports(Storage storage) {
                                this.storage = storage;
                            }

                            public ScheduledReport daily() {
                                return new ScheduledReport(storage);
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().injectServices().evaluate()).isEmpty();
    }

    @Test
    void callingTheSuperConstructorIsNotCreatingAService() {
        var guard = new CompiledProject(directory).release(release)
                .add("Gateway", """
                        package shop;

                        public abstract class Gateway {
                            public long now() {
                                return System.currentTimeMillis();
                            }
                        }
                        """)
                .add("StripeGateway", """
                        package shop;

                        public final class StripeGateway extends Gateway {
                            public StripeGateway() {
                                super();
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().injectServices().evaluate()).isEmpty();
    }

    @Test
    void creatingTheSuperclassInAConstructorIsCreatingAService() {
        var guard = new CompiledProject(directory).release(release)
                .add("Gateway", """
                        package shop;

                        public class Gateway {
                            public long now() {
                                return System.currentTimeMillis();
                            }
                        }
                        """)
                .add("CachingGateway", """
                        package shop;

                        public final class CachingGateway extends Gateway {
                            private final Gateway fallback;

                            public CachingGateway() {
                                super();
                                this.fallback = new Gateway();
                            }

                            public long fallbackNow() {
                                return fallback.now();
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().injectServices().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(tuple(8, "CachingGateway creates service-like Gateway with new"));
    }

    @Test
    void wiringPackagesMayCreateServices() {
        var configured = ShopFixture.write(directory).release(release)
                .add("PaymentsConfig", """
                        package shop.config;

                        import shop.payments.StripeClient;
                        import shop.payments.StripeGateway;

                        public class PaymentsConfig {
                            public StripeGateway gateway() {
                                return new StripeGateway(new StripeClient());
                            }
                        }
                        """)
                .configure(SHOP);

        assertThat(configured.build().dependencies().injectServices().evaluate()).isNotEmpty();
        assertThat(configured.wiringIn("..config..").build().dependencies().injectServices().evaluate()).isEmpty();
    }
}
