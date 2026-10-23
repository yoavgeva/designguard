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
class TestSubjectRuleTest {

    private static final String REPORT = "Report";
    private static final String REPORT_TEST = "ReportTest";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void testsExerciseTheClassTheyAreNamedAfter() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("OrderService", "package shop;\n\npublic class OrderService {\n    public int place() {\n"
                        + "        return 1;\n    }\n}\n")
                .add("Prices", "package shop;\n\npublic final class Prices {\n    private Prices() {\n    }\n\n"
                        + "    public static long total(long cents) {\n        return cents;\n    }\n}\n")
                .add("Checkout", "package shop;\n\npublic class Checkout {\n    public void pay() {\n    }\n}\n")
                .add(REPORT, "package shop;\n\npublic class Report {\n}\n")
                .addTest("OrderServiceTest", """
                        package shop;

                        class OrderServiceTest {
                            @org.junit.jupiter.api.Test
                            void places() {
                                org.assertj.core.api.Assertions.assertThat(new OrderService().place()).isEqualTo(1);
                            }
                        }
                        """)
                .addTest("PricesTest", """
                        package shop;

                        class PricesTest {
                            @org.junit.jupiter.api.Test
                            void totals() {
                                org.assertj.core.api.Assertions.assertThat(Prices.total(5)).isEqualTo(5);
                            }
                        }
                        """)
                .addTest("CheckoutTest", """
                        package shop;

                        import static org.mockito.Mockito.mock;
                        import static org.mockito.Mockito.verify;

                        class CheckoutTest {
                            @org.junit.jupiter.api.Test
                            void pays() {
                                Checkout checkout = mock(Checkout.class);
                                checkout.pay();
                                verify(checkout).pay();
                            }
                        }
                        """)
                .addTest(REPORT_TEST, """
                        package shop;

                        class ReportTest {
                            @org.junit.jupiter.api.Test
                            void adds() {
                                org.assertj.core.api.Assertions.assertThat(1 + 1).isEqualTo(2);
                            }
                        }
                        """)
                .add("Invoice", "package shop;\n\npublic class Invoice {\n    public long total() {\n        return 7;\n    }\n}\n")
                .addTest("InvoiceFixture", """
                        package shop;

                        final class InvoiceFixture {
                            private InvoiceFixture() {
                            }

                            static long total() {
                                return new Invoice().total();
                            }
                        }
                        """)
                .addTest("InvoiceTest", """
                        package shop;

                        class InvoiceTest {
                            @org.junit.jupiter.api.Test
                            void totals() {
                                org.assertj.core.api.Assertions.assertThat(InvoiceFixture.total()).isEqualTo(7);
                            }
                        }
                        """)
                .addTest("ShippingTest", """
                        package shop;

                        class ShippingTest {
                            @org.junit.jupiter.api.Test
                            void ships() {
                                org.assertj.core.api.Assertions.assertThat("box").isNotEmpty();
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.tests().testsUseTheirSubject().evaluate())
                .extracting(Violation::message, Violation::fix)
                .containsExactly(
                        tuple("CheckoutTest only mocks Checkout, the class it's named after",
                                "test a real Checkout, and mock only its collaborators"),
                        tuple("ReportTest never uses Report, the class it's named after",
                                "exercise Report in the test, or name the test after what it tests"));
    }

    @Test
    void callsTheSymbolSolverFindsAmbiguousDontStopTheCheck() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add(REPORT, "package shop;\n\npublic class Report {\n}\n")
                .addTest(REPORT_TEST, "package shop;\n\nclass ReportTest {\n    @org.junit.jupiter.api.Test\n"
                        + "    void compares() {\n        org.junit.jupiter.api.Assertions.assertArrayEquals(null, null);\n"
                        + "    }\n}\n")
                .guard("shop");

        assertThat(guard.tests().testsUseTheirSubject().evaluate()).extracting(Violation::message)
                .containsExactly("ReportTest never uses Report, the class it's named after");
    }

    @Test
    void testsWithTheSameNameInDifferentPackagesAreEachChecked() {
        String test = "class ReportTest {\n    @org.junit.jupiter.api.Test\n    void adds() {\n"
                + "        org.assertj.core.api.Assertions.assertThat(1 + 1).isEqualTo(2);\n    }\n}\n";
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add(REPORT, "package shop.sales;\n\npublic class Report {\n}\n")
                .addTest(REPORT_TEST, "package shop.sales;\n\n" + test)
                .add(REPORT, "package shop.billing;\n\npublic class Report {\n}\n")
                .addTest(REPORT_TEST, "package shop.billing;\n\n" + test)
                .guard("shop");

        assertThat(guard.tests().testsUseTheirSubject().evaluate()).extracting(Violation::file)
                .hasSize(2).anyMatch(file -> file.endsWith("shop/sales/ReportTest.java"))
                .anyMatch(file -> file.endsWith("shop/billing/ReportTest.java"));
    }
}
