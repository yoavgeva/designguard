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

    private static final String SHOP = "shop";
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
                .guard(SHOP);

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
                .guard(SHOP);

        assertThat(guard.tests().testsUseTheirSubject().evaluate()).extracting(Violation::message)
                .containsExactly("ReportTest never uses Report, the class it's named after");
    }

    @Test
    void annotationsAndObjectsMadeByFactoriesAreUses() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Label", "package shop;\n\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface Label {\n    String value();\n}\n")
                .add("Streams", """
                        package shop;

                        public final class Streams {
                            private Streams() {
                            }

                            public static Pipe pipe(String text) {
                                return new Pipe(text);
                            }

                            public static final class Pipe {
                                private final String text;

                                Pipe(String text) {
                                    this.text = text;
                                }

                                public String text() {
                                    return text;
                                }
                            }
                        }
                        """)
                .addTest("LabelTest", """
                        package shop;

                        class LabelTest {
                            @Label("name")
                            private String named;

                            @org.junit.jupiter.api.Test
                            void reads() throws Exception {
                                org.assertj.core.api.Assertions.assertThat(getClass().getDeclaredField("named")
                                        .getAnnotations()).hasSize(1);
                            }
                        }
                        """)
                .addTest("PipeTest", """
                        package shop;

                        class PipeTest {
                            @org.junit.jupiter.api.Test
                            void pipes() {
                                org.assertj.core.api.Assertions.assertThat(Streams.pipe("a").text()).isEqualTo("a");
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.tests().testsUseTheirSubject().evaluate()).isEmpty();
    }

    @Test
    void staticallyImportedMembersAreUses() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Base64Url", """
                        package shop;

                        final class Base64Url {
                            static final String PADDING = "=";

                            private Base64Url() {
                            }

                            static String encode(String text) {
                                return java.util.Base64.getUrlEncoder().encodeToString(text.getBytes());
                            }
                        }
                        """)
                .add("Slugs", """
                        package shop;

                        final class Slugs {
                            private Slugs() {
                            }

                            static String slug(String title) {
                                return title.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
                            }
                        }
                        """)
                .add("Hashes", """
                        package shop;

                        final class Hashes {
                            private Hashes() {
                            }

                            static int hash(String text) {
                                return text.hashCode();
                            }
                        }
                        """)
                .addTest("Base64UrlTest", """
                        package shop;

                        import static org.assertj.core.api.Assertions.assertThat;
                        import static shop.Base64Url.encode;

                        class Base64UrlTest {
                            @org.junit.jupiter.api.Test
                            void encodes() {
                                assertThat(encode("something")).isEqualTo("c29tZXRoaW5n");
                            }
                        }
                        """)
                .addTest("SlugsTest", """
                        package shop;

                        import static org.assertj.core.api.Assertions.assertThat;
                        import static shop.Slugs.*;

                        class SlugsTest {
                            @org.junit.jupiter.api.Test
                            void slugs() {
                                assertThat(slug("Hello World")).isEqualTo("hello-world");
                            }
                        }
                        """)
                .addTest("HashesTest", """
                        package shop;

                        import static org.assertj.core.api.Assertions.assertThat;
                        import static shop.Base64Url.PADDING;

                        class HashesTest {
                            @org.junit.jupiter.api.Test
                            void hashes() {
                                assertThat(PADDING).isEqualTo("=");
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.tests().testsUseTheirSubject().evaluate()).extracting(Violation::message)
                .containsExactly("HashesTest never uses Hashes, the class it's named after");
    }

    @Test
    void webEndpointsAreUsedThroughRequestsAndBeansThroughTheirInterfaces() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("RestController", "package org.springframework.web.bind.annotation;\n\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface RestController {\n}\n")
                .add("Filter", "package jakarta.servlet;\n\npublic interface Filter {\n    void doFilter(Object request);\n}\n")
                .add("UserDetailsService", "package org.springframework.security.core.userdetails;\n\n"
                        + "public interface UserDetailsService {\n    String loadUserByUsername(String name);\n}\n")
                .add("UserResource", """
                        package shop;

                        @org.springframework.web.bind.annotation.RestController
                        public class UserResource {
                            public String users() {
                                return "users";
                            }
                        }
                        """)
                .add("SpaFilter", """
                        package shop;

                        public class SpaFilter implements jakarta.servlet.Filter {
                            @Override
                            public void doFilter(Object request) {
                                request.hashCode();
                            }
                        }
                        """)
                .add("DomainUserDetailsService", """
                        package shop;

                        public class DomainUserDetailsService
                                implements org.springframework.security.core.userdetails.UserDetailsService {
                            @Override
                            public String loadUserByUsername(String name) {
                                return name;
                            }
                        }
                        """)
                .add("Reports", "package shop;\n\npublic class Reports {\n    public String report() {\n"
                        + "        return \"report\";\n    }\n}\n")
                .addTest("UserResourceIT", """
                        package shop;

                        import org.springframework.test.web.servlet.MockMvc;

                        class UserResourceIT {
                            @org.springframework.beans.factory.annotation.Autowired
                            private MockMvc mvc;

                            @org.junit.jupiter.api.Test
                            void lists() throws Exception {
                                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users"));
                            }
                        }
                        """)
                .addTest("SpaFilterIT", """
                        package shop;

                        class SpaFilterIT {
                            private org.springframework.test.web.reactive.server.WebTestClient client;

                            @org.junit.jupiter.api.Test
                            void forwards() {
                                client.get().uri("/admin").exchange().expectStatus().isOk();
                            }
                        }
                        """)
                .addTest("DomainUserDetailsServiceIT", """
                        package shop;

                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.security.core.userdetails.UserDetailsService;

                        class DomainUserDetailsServiceIT {
                            @Autowired
                            private UserDetailsService service;

                            @org.junit.jupiter.api.Test
                            void loads() {
                                org.assertj.core.api.Assertions.assertThat(service.loadUserByUsername("a")).isEqualTo("a");
                            }
                        }
                        """)
                .addTest("ReportsIT", """
                        package shop;

                        import org.springframework.test.web.servlet.MockMvc;

                        class ReportsIT {
                            private MockMvc mvc;

                            @org.junit.jupiter.api.Test
                            void reports() throws Exception {
                                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/reports"));
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.tests().testsUseTheirSubject().evaluate()).extracting(Violation::message)
                .containsExactly("ReportsIT never uses Reports, the class it's named after");
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
                .guard(SHOP);

        assertThat(guard.tests().testsUseTheirSubject().evaluate()).extracting(Violation::file)
                .hasSize(2).anyMatch(file -> file.endsWith("shop/sales/ReportTest.java"))
                .anyMatch(file -> file.endsWith("shop/billing/ReportTest.java"));
    }
}
