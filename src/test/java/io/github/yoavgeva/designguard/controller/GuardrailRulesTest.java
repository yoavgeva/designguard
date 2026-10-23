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

        assertThat(guard.tests().serviceLikeClassesHaveTests().evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("services-have-tests");
            assertThat(violation.message())
                    .isEqualTo("OrderService is service-like, but has no test class (looked for OrderServiceTest)");
            assertThat(violation.fix()).isEqualTo("add OrderServiceTest that exercises its behaviour");
        });
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
                .add("App", "package shop;\n\npublic final class App {\n"
                        + "    public static void main(String[] args) {\n        new Orders().place();\n    }\n}\n")
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
}
