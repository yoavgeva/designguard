package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InjectServicesRuleTest {

    private static final String SHOP = "shop";

    @TempDir
    private Path directory;

    @Test
    void flagsServicesCreatedOutsideWiringCode() {
        var guard = ShopFixture.write(directory)
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
        var guard = ShopFixture.write(directory)
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
    void creatingPureObjectsAndCallingFactoriesIsFine() {
        var guard = new CompiledProject(directory)
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
    void callingTheSuperConstructorIsNotCreatingAService() {
        var guard = new CompiledProject(directory)
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
    void wiringPackagesMayCreateServices() {
        var guard = ShopFixture.write(directory)
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
                .guard(SHOP);

        assertThat(guard.dependencies().injectServices().evaluate()).isNotEmpty();
        assertThat(guard.wiringIn("..config..").dependencies().injectServices().evaluate()).isEmpty();
    }
}
