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
class GuardrailRulesTest {

    private static final String SHOP = "shop";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void serviceLikeClassesNeedATestClass() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("StripeClient", "package shop;\n\npublic class StripeClient {\n"
                        + "    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();\n"
                        + "    public String charge() {\n        return http.toString();\n    }\n}\n")
                .add("OrderService", "package shop;\n\npublic class OrderService {\n"
                        + "    public String pay(StripeClient client) {\n        return client.charge();\n    }\n}\n")
                .add("Money", "package shop;\n\npublic record Money(long cents) {\n}\n")
                .addTest("StripeClientTest", "package shop;\n\nclass StripeClientTest {\n}\n")
                .guard(SHOP);

        assertThat(guard.guardrails().serviceLikeClassesHaveTests().evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("services-have-tests");
            assertThat(violation.message())
                    .isEqualTo("OrderService is service-like, but has no test class (looked for OrderServiceTest)");
            assertThat(violation.fix()).isEqualTo("add OrderServiceTest that exercises its behaviour");
        });
    }

    @Test
    void aTestNamedAfterAnInterfaceTestsTheImplementationsItUses() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("ViewNameResolver", "package shop;\n\npublic interface ViewNameResolver {\n    String resolve(String name);\n}\n")
                .add("DefaultViewNameResolver", "package shop;\n\npublic class DefaultViewNameResolver implements ViewNameResolver {\n"
                        + "    public String resolve(String name) {\n        return System.getenv(name);\n    }\n}\n")
                .add("ThemeViewNameResolver", "package shop;\n\npublic class ThemeViewNameResolver implements ViewNameResolver {\n"
                        + "    public String resolve(String name) {\n        return System.getenv(\"THEME\") + name;\n    }\n}\n")
                .addTest("ViewNameResolverTest", "package shop;\n\nclass ViewNameResolverTest {\n"
                        + "    private final DefaultViewNameResolver resolver = new DefaultViewNameResolver();\n}\n")
                .guard(SHOP);

        assertThat(guard.guardrails().serviceLikeClassesHaveTests().evaluate()).extracting(Violation::message).containsExactly(
                "ThemeViewNameResolver is service-like, but has no test class (looked for ThemeViewNameResolverTest)");
    }

    @Test
    void unusedPublicCodeIsReported() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Line", "package shop;\n\npublic record Line(int quantity) {\n}\n")
                .add("Orders", """
                        package shop;

                        public class Orders {
                            private final String name = "orders";

                            public String place() {
                                return name;
                            }

                            public String archive(Line line) {
                                return name + line;
                            }

                            public String getName() {
                                return name;
                            }

                            @Override
                            public String toString() {
                                return name;
                            }
                        }
                        """)
                .add("Unused", "package shop;\n\npublic class Unused {\n    public int size() {\n        return 0;\n    }\n}\n")
                .add("QueueMXBean", "package shop;\n\npublic interface QueueMXBean {\n    int pending();\n\n    void pause();\n}\n")
                .add("Queue", """
                        package shop;

                        public final class Queue implements QueueMXBean {
                            @Override
                            public int pending() {
                                return 0;
                            }

                            @Override
                            public void pause() {
                                // nothing to pause: the queue is drained on every call
                            }
                        }
                        """)
                .add("App", "package shop;\n\npublic final class App {\n"
                        + "    public static void main(String[] args) {\n        new Orders().place();\n"
                        + "        QueueMXBean queue = new Queue();\n        System.out.println(queue);\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.guardrails().noUnusedPublicCode().evaluate()).extracting(Violation::message, Violation::fix)
                .containsExactly(
                        tuple("Orders.archive() is public, but nothing calls it",
                                "delete it, or make it private until something needs it"),
                        tuple("Unused is public, but nothing uses it",
                                "delete it, or make it package-private until something needs it"));
    }

    @Test
    void classesUsedOnlyForTheirConstantsAreUsed() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Limits", "package shop;\n\npublic final class Limits {\n"
                        + "    public static final int MAX_ITEMS = 10;\n\n    private Limits() {\n    }\n}\n")
                .add("App", "package shop;\n\npublic final class App {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        System.out.println(Limits.MAX_ITEMS);\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.guardrails().noUnusedPublicCode().evaluate()).isEmpty();
    }

    @Test
    void typesAFrameworkFindsAndCreatesAreUsed() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Repository", "package org.springframework.data.repository;\n\n"
                        + "public interface Repository<T, ID> {\n}\n")
                .add("ServletInitializer", "package org.springframework.boot.web.servlet.support;\n\n"
                        + "public abstract class ServletInitializer {\n    protected abstract String sources();\n}\n")
                .add("ApplicationWebXml", """
                        package shop;

                        public class ApplicationWebXml extends org.springframework.boot.web.servlet.support.ServletInitializer {
                            @Override
                            protected String sources() {
                                return "shop";
                            }
                        }
                        """)
                .add("OrderQueries", "package shop;\n\npublic interface OrderQueries {\n    String latest();\n}\n")
                .add("OrderRepository", "package shop;\n\npublic interface OrderRepository "
                        + "extends org.springframework.data.repository.Repository<String, Long>, OrderQueries {\n}\n")
                .add("OrderQueriesImpl", """
                        package shop;

                        public class OrderQueriesImpl implements OrderQueries {
                            @Override
                            public String latest() {
                                return "latest";
                            }
                        }
                        """)
                .add("Checkout", """
                        package shop;

                        final class Checkout {
                            private final OrderRepository orders;

                            Checkout(OrderRepository orders) {
                                this.orders = orders;
                            }

                            String latest() {
                                return orders.latest();
                            }
                        }
                        """)
                .add("Pricing", "package shop;\n\npublic interface Pricing {\n    long price();\n}\n")
                .add("FixedPricing", """
                        package shop;

                        public class FixedPricing implements Pricing {
                            @Override
                            public long price() {
                                return 1;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.guardrails().noUnusedPublicCode().evaluate()).extracting(Violation::message).containsExactly(
                "FixedPricing is public, but nothing uses it",
                "Pricing.price() is public, but nothing calls it");
    }
}
