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
class MockedValuesRuleTest {

    private static final String SHOP = "shop";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void testsMockOnlyServiceLikeTypes() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Money", "package shop;\n\npublic record Money(long cents) {\n}\n")
                .add("Status", "package shop;\n\npublic enum Status { OPEN, PAID }\n")
                .add("Order", "package shop;\n\npublic record Order(Status status) {\n"
                        + "    public record Line(int quantity) {\n    }\n}\n")
                .add("Shape", "package shop.shapes;\n\npublic sealed interface Shape permits Circle {\n}\n")
                .add("Circle", "package shop.shapes;\n\npublic record Circle(double radius) implements Shape {\n}\n")
                .add("Notifier", "package shop;\n\npublic interface Notifier {\n    void send(String text);\n}\n")
                .add("Pricing", "package shop;\n\npublic interface Pricing {\n    long price(String item);\n}\n")
                .add("FlatPricing", "package shop;\n\npublic record FlatPricing(long cents) implements Pricing {\n"
                        + "    public long price(String item) {\n        return cents;\n    }\n}\n")
                .add("PaymentGateway", "package shop;\n\npublic interface PaymentGateway {\n    void charge(Money price);\n}\n")
                .add("StripeGateway", """
                        package shop;

                        public final class StripeGateway implements PaymentGateway {
                            private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

                            @Override
                            public void charge(Money price) {
                                http.toString();
                            }
                        }
                        """)
                .add("Checkout", """
                        package shop;

                        public class Checkout {
                            private final PaymentGateway gateway;

                            public Checkout(PaymentGateway gateway) {
                                this.gateway = gateway;
                            }

                            public void pay(Money price) {
                                gateway.charge(price);
                            }
                        }
                        """)
                .addTest("CheckoutTest", """
                        package shop;

                        import static org.mockito.Mockito.mock;

                        import org.junit.jupiter.api.Test;
                        import org.mockito.Mock;
                        import shop.shapes.*;

                        class CheckoutTest {
                            @Mock
                            private PaymentGateway gateway;

                            @Mock
                            private Money price;

                            @Test
                            void pays() {
                                Status status = mock(Status.class);
                                Order.Line line = org.mockito.Mockito.mock(Order.Line.class);
                                Shape shape = mock(Shape.class);
                                Notifier notifier = mock(Notifier.class);
                                Checkout checkout = mock(Checkout.class);
                                java.util.List<?> names = mock(java.util.List.class);
                                Pricing pricing = mock(Pricing.class);
                                Money spied = org.mockito.Mockito.spy(new Money(5));
                                new Checkout(gateway).pay(price);
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.tests().testsDontMockValues().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(14, "CheckoutTest mocks Money, which is a value"),
                        tuple(18, "CheckoutTest mocks Status, which is a value"),
                        tuple(19, "CheckoutTest mocks Order.Line, which is a value"),
                        tuple(20, "CheckoutTest mocks Shape, which is a value"),
                        tuple(25, "CheckoutTest mocks Money, which is a value"));
        assertThat(guard.tests().testsDontMockValues().evaluate().get(0).fix())
                .isEqualTo("use a real Money instead; mock only service-like types");
    }

    @Test
    void wiringCodeIsNotAValue() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Configuration", "package org.springframework.context.annotation;\n\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface Configuration {\n}\n")
                .add("WebConfiguration", """
                        package shop;

                        @org.springframework.context.annotation.Configuration
                        public class WebConfiguration {
                            public String resources() {
                                return "/static";
                            }
                        }
                        """)
                .addTest("WebConfigurationTest", """
                        package shop;

                        class WebConfigurationTest {
                            @org.junit.jupiter.api.Test
                            void servesResources() {
                                WebConfiguration configuration = org.mockito.Mockito.spy(new WebConfiguration());
                                org.assertj.core.api.Assertions.assertThat(configuration.resources()).isEqualTo("/static");
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.tests().testsDontMockValues().evaluate()).isEmpty();
    }

    @Test
    void classesThatWorkThroughALibraryAreNoValuesDesignguardCanVouchFor() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Directory", "package org.apache.lucene.store;\n\npublic abstract class Directory {\n"
                        + "    public abstract String[] listAll();\n}\n")
                .add("DefaultPluginManager", "package org.pf4j;\n\npublic class DefaultPluginManager {\n"
                        + "    public void loadPlugins() {\n    }\n}\n")
                .add("PathResource", "package org.springframework.core.io;\n\npublic class PathResource {\n"
                        + "    public PathResource(String path) {\n    }\n\n    public String getFilename() {\n        return \"\";\n    }\n}\n")
                .add("PasswordEncoder", "package org.springframework.security.crypto.password;\n\n"
                        + "public interface PasswordEncoder {\n    boolean matches(CharSequence raw, String encoded);\n}\n")
                .add("SearchEngine", """
                        package shop;

                        public final class SearchEngine {
                            private final org.apache.lucene.store.Directory directory;

                            public SearchEngine(org.apache.lucene.store.Directory directory) {
                                this.directory = directory;
                            }

                            public int count() {
                                return directory.listAll().length;
                            }
                        }
                        """)
                .add("PluginManager", "package shop;\n\npublic class PluginManager extends org.pf4j.DefaultPluginManager {\n}\n")
                .add("PluginFinder", """
                        package shop;

                        public final class PluginFinder {
                            public String find(String path) {
                                return new org.springframework.core.io.PathResource(path).getFilename();
                            }
                        }
                        """)
                .add("User", """
                        package shop;

                        public final class User {
                            private final String hash;

                            public User(String hash) {
                                this.hash = hash;
                            }

                            public boolean matches(String raw, org.springframework.security.crypto.password.PasswordEncoder encoder) {
                                return encoder.matches(raw, hash);
                            }
                        }
                        """)
                .addTest("ShopTest", """
                        package shop;

                        import static org.mockito.Mockito.mock;

                        class ShopTest {
                            @org.junit.jupiter.api.Test
                            void mocks() {
                                SearchEngine engine = mock(SearchEngine.class);
                                PluginManager manager = mock(PluginManager.class);
                                PluginFinder finder = mock(PluginFinder.class);
                                User user = mock(User.class);
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.tests().testsDontMockValues().evaluate()).extracting(Violation::message)
                .containsExactly("ShopTest mocks User, which is a value");
    }

    @Test
    void aValueMockedOnlyToSeeItCalledObservesTheCode() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Buffer", """
                        package shop;

                        public class Buffer {
                            private final StringBuilder text = new StringBuilder();

                            public void append(String more) {
                                text.append(more);
                            }

                            public void close() {
                                // nothing to release
                            }

                            public int length() {
                                return text.length();
                            }
                        }
                        """)
                .add("Tee", """
                        package shop;

                        public class Tee {
                            private final Buffer first;
                            private final Buffer second;

                            public Tee(Buffer first, Buffer second) {
                                this.first = first;
                                this.second = second;
                            }

                            public void close() {
                                first.close();
                                second.close();
                            }

                            public int length() {
                                return first.length() + second.length();
                            }
                        }
                        """)
                .addTest("TeeTest", """
                        package shop;

                        import static org.mockito.Mockito.mock;
                        import static org.mockito.Mockito.spy;
                        import static org.mockito.Mockito.verify;
                        import static org.mockito.Mockito.when;

                        class TeeTest {
                            private final Buffer spied = spy(new Buffer());
                            private Buffer original;

                            @org.junit.jupiter.api.BeforeEach
                            void setUp() {
                                original = spy(new Buffer());
                            }

                            @org.junit.jupiter.api.Test
                            void closesTheOriginal() {
                                new Tee(original, spied).close();
                                verify(original).close();
                            }

                            @org.junit.jupiter.api.Test
                            void closesBoth() {
                                Buffer closed = mock(Buffer.class);
                                new Tee(closed, spied).close();
                                verify(closed).close();
                                verify(spied).close();
                            }

                            @org.junit.jupiter.api.Test
                            void addsLengths() {
                                Buffer stubbed = mock(Buffer.class);
                                when(stubbed.length()).thenReturn(3);
                                Buffer dummy = mock(Buffer.class);
                                org.assertj.core.api.Assertions.assertThat(new Tee(stubbed, dummy).length()).isEqualTo(3);
                            }

                            @org.junit.jupiter.api.Test
                            void asksBothForTheirLength() {
                                Buffer asked = mock(Buffer.class);
                                new Tee(asked, spied).length();
                                verify(asked).length();
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.tests().testsDontMockValues().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(33, "TeeTest mocks Buffer, which is a value"),
                        tuple(35, "TeeTest mocks Buffer, which is a value"),
                        tuple(41, "TeeTest mocks Buffer, which is a value"));
    }
}
