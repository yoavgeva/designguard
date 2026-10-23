package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiRulesTest {

    private static final String SHOP = "shop";

    @TempDir
    private Path directory;

    @Test
    void packagesMustBeNullMarked() {
        DesignGuard guard = new CompiledProject(directory)
                .add("NullMarked", "package org.jspecify.annotations;\n\npublic @interface NullMarked {\n}\n")
                .add("package-info", "@NullMarked\npackage shop.orders;\n\nimport org.jspecify.annotations.NullMarked;\n")
                .add("Order", "package shop.orders;\n\npublic class Order {\n}\n")
                .add("Payment", "package shop.payments;\n\npublic class Payment {\n}\n")
                .guard(SHOP);

        assertThat(guard.api().packagesAreNullMarked().evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("null-marked");
            assertThat(violation.file()).endsWith("shop/payments/Payment.java");
            assertThat(violation.message()).isEqualTo("package shop.payments isn't @NullMarked, so its nullness is unspecified");
            assertThat(violation.fix()).isEqualTo("add package-info.java to the package:"
                    + " @org.jspecify.annotations.NullMarked package shop.payments;");
        });
    }

    @Test
    void optionalIsOnlyForReturnValues() {
        DesignGuard guard = new CompiledProject(directory)
                .add("Order", """
                        package shop;

                        import java.util.Optional;

                        public class Order {
                            private final Optional<String> note;

                            public Order(Optional<String> note) {
                                this.note = note;
                            }

                            public Optional<String> note() {
                                return note;
                            }

                            public Optional<String> coupon() {
                                return null;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.api().optionalUsage().evaluate()).extracting(Violation::message, Violation::fix).containsExactly(
                tuple("Order has a field 'note' of type Optional",
                        "store the value itself, or null, and return an Optional from its accessor instead"),
                tuple("Order's constructor takes an Optional parameter",
                        "take the value itself, or offer an overload without it"),
                tuple("Order.coupon() returns null although its result is an Optional",
                        "return Optional.empty() instead of null"));
    }

    @Test
    void checkedExceptionsStayOutOfTheApi() {
        DesignGuard guard = new CompiledProject(directory)
                .add("OrderException", "package shop;\n\npublic class OrderException extends Exception {\n}\n")
                .add("Orders", """
                        package shop;

                        public class Orders implements AutoCloseable {
                            public void place() throws java.io.IOException {
                                new java.io.File("x").getCanonicalPath();
                            }

                            public void cancel() throws OrderException {
                                throw new OrderException();
                            }

                            @Override
                            public void close() throws Exception {
                                place();
                            }

                            public void run() throws IllegalStateException {
                                throw new IllegalStateException("not ready");
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.api().noCheckedExceptions().evaluate()).extracting(Violation::message).containsExactly(
                "OrderException is a checked exception",
                "Orders.place() declares the checked exception IOException",
                "Orders.cancel() declares the checked exception OrderException");
    }

    @Test
    void publicClassesUsedOnlyInTheirPackageShouldBePackagePrivate() {
        DesignGuard guard = new CompiledProject(directory)
                .add("OrderMapper", "package shop.orders;\n\npublic class OrderMapper {\n"
                        + "    public String map(String value) {\n        return value;\n    }\n}\n")
                .add("Orders", "package shop.orders;\n\npublic class Orders {\n"
                        + "    public String run() {\n        return new OrderMapper().map(\"x\");\n    }\n}\n")
                .add("Controller", "package shop.web;\n\npublic class Controller {\n"
                        + "    public String go() {\n        return new shop.orders.Orders().run();\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.api().minimalVisibility().evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("minimal-visibility");
            assertThat(violation.message()).isEqualTo("OrderMapper is public, but only package shop.orders uses it");
            assertThat(violation.fix()).isEqualTo("make it package-private");
        });
    }
}
