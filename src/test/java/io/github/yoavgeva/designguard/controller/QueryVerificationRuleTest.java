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
class QueryVerificationRuleTest {

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void testsStubQueriesAndVerifyOnlyCommands() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("PriceList", "package shop;\n\npublic interface PriceList {\n    long priceOf(String item);\n\n"
                        + "    void refresh();\n}\n")
                .add("Mailer", "package shop;\n\npublic interface Mailer {\n    void send(String to);\n}\n")
                .add("Checkout", """
                        package shop;

                        public class Checkout {
                            private final PriceList prices;
                            private final Mailer mailer;

                            public Checkout(PriceList prices, Mailer mailer) {
                                this.prices = prices;
                                this.mailer = mailer;
                            }

                            public void pay(String item) {
                                mailer.send("ada@example.com " + prices.priceOf(item));
                            }
                        }
                        """)
                .addTest("CheckoutTest", """
                        package shop;

                        import static org.mockito.Mockito.mock;
                        import static org.mockito.Mockito.never;
                        import static org.mockito.Mockito.times;
                        import static org.mockito.Mockito.verify;

                        class CheckoutTest {
                            private final PriceList prices = mock(PriceList.class);
                            private final Mailer mailer = mock(Mailer.class);

                            @org.junit.jupiter.api.Test
                            void pays() {
                                new Checkout(prices, mailer).pay("book");
                                verify(prices).priceOf("book");
                                verify(mailer).send("ada@example.com");
                                verify(prices, never()).refresh();
                                verify(prices, times(1)).priceOf("book");
                            }

                            @org.junit.jupiter.api.Test
                            void refreshes(PriceList injected) {
                                org.mockito.Mockito.verify(injected).priceOf("pen");
                                var local = mock(Mailer.class);
                                verify(local).send("x");
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.guardrails().testsCheckResults().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(15, "test CheckoutTest.pays() verifies PriceList.priceOf(), a query that returns a value"),
                        tuple(23, "test CheckoutTest.refreshes() verifies PriceList.priceOf(), a query that returns a value"));
        assertThat(guard.guardrails().testsCheckResults().evaluate().get(0).fix()).isEqualTo("stub it with"
                + " when(...).thenReturn(...) and assert on what the code under test returns; verify only commands");
    }
}
