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
class ApiRulesTest {

    private static final String SHOP = "shop";
    private static final String ORDER = "Order";
    private static final String ORDERS = "Orders";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void packagesMustBeNullMarked() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("NullMarked", "package org.jspecify.annotations;\n\npublic @interface NullMarked {\n}\n")
                .add("package-info", "@NullMarked\npackage shop.orders;\n\nimport org.jspecify.annotations.NullMarked;\n")
                .add(ORDER, "package shop.orders;\n\npublic class Order {\n}\n")
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
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add(ORDER, """
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
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("OrderException", "package shop;\n\npublic class OrderException extends Exception {\n}\n")
                .add(ORDERS, """
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
    void publicApisUseTypesNotObjectsOrMaps() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("JsonNode", "package com.fasterxml.jackson.databind;\n\npublic abstract class JsonNode {\n}\n")
                .add(ORDER, "package shop;\n\npublic record Order(String name) {\n}\n")
                .add(ORDERS, """
                        package shop;

                        import java.util.Map;

                        public class Orders {
                            public Order create(Map<String, Object> fields) {
                                return new Order(String.valueOf(fields.get("name")));
                            }

                            public Object find(String name) {
                                return new Order(name);
                            }

                            public <T> T convert(Class<T> type) {
                                return type.cast(find("x"));
                            }

                            public Map<String, Integer> counts() {
                                return Map.of("orders", 1);
                            }

                            public com.fasterxml.jackson.databind.JsonNode export() {
                                return null;
                            }

                            @Override
                            public boolean equals(Object other) {
                                return this == other;
                            }

                            @Override
                            public int hashCode() {
                                return 1;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.api().typedApis().evaluate()).extracting(Violation::message).containsExactly(
                "Orders.create() takes an untyped Map<String, Object>",
                "Orders.find() returns an untyped Object",
                "Orders.export() returns an untyped JsonNode");
        assertThat(guard.api().typedApis().evaluate().get(0).fix())
                .isEqualTo("define a record with named, typed fields and use it instead");
    }

    @Test
    void accessorsChangeNoFields() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Counter", """
                        package shop;

                        import java.util.List;

                        public class Counter {
                            private int count;
                            private List<String> names;
                            private boolean dirty;

                            public int getNext() {
                                return count++;
                            }

                            public List<String> getNames() {
                                if (names == null) {
                                    names = List.of("first");
                                }
                                return names;
                            }

                            public boolean isDirty() {
                                boolean was = dirty;
                                dirty = false;
                                return was;
                            }

                            public boolean hasCount() {
                                return count > 0;
                            }

                            public int getAndReset() {
                                int was = count;
                                count = 0;
                                return was;
                            }

                            public void setCount(int count) {
                                this.count = count;
                            }

                            private volatile String label;

                            public String getLabel() {
                                String result = label;
                                if (result == null) {
                                    synchronized (this) {
                                        result = label;
                                        if (result == null) {
                                            result = "computed";
                                            label = result;
                                        }
                                    }
                                }
                                return result;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.api().accessorsChangeNothing().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(11, "Counter.getNext() changes the field 'count'"),
                        tuple(23, "Counter.isDirty() changes the field 'dirty'"));
        assertThat(guard.api().accessorsChangeNothing().evaluate().get(0).fix()).isEqualTo("keep get, is and has"
                + " methods free of side effects: move the change into a command method, or compute the value without"
                + " storing it");
    }

    @Test
    void choicesBetweenStringsAreEnums() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Delivery", "package shop;\n\npublic enum Delivery { EXPRESS, PICKUP, STANDARD }\n")
                .add("Fees", """
                        package shop;

                        public class Fees {
                            public int fee(String delivery) {
                                switch (delivery) {
                                    case "express":
                                        return 10;
                                    case "pickup":
                                        return 0;
                                    case "in-store":
                                        return 5;
                                    default:
                                        return -1;
                                }
                            }

                            public int tax(String region) {
                                if (region.equals("eu")) {
                                    return 20;
                                } else if ("us".equals(region)) {
                                    return 8;
                                } else if (region.equalsIgnoreCase("uk")) {
                                    return 17;
                                }
                                return 0;
                            }

                            public int size(String size) {
                                return switch (size) {
                                    case "s" -> 1;
                                    case "m" -> 2;
                                    default -> 3;
                                };
                            }

                            public static Delivery parse(String text) {
                                switch (text) {
                                    case "express":
                                        return Delivery.EXPRESS;
                                    case "pickup":
                                        return Delivery.PICKUP;
                                    case "standard":
                                        return Delivery.STANDARD;
                                    default:
                                        throw new IllegalArgumentException(text);
                                }
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.api().noStringlyTypedChoices().evaluate()).extracting(Violation::line, Violation::message, Violation::fix)
                .containsExactly(
                        tuple(5, "Fees.fee() switches on the strings \"express\", \"pickup\" and \"in-store\": make them an enum",
                                "replace the strings with an enum, e.g. enum Delivery { EXPRESS, PICKUP, IN_STORE }, and"
                                        + " switch over it"),
                        tuple(18, "Fees.tax() compares region with the strings \"eu\", \"us\" and \"uk\": make them an enum",
                                "replace the strings with an enum, e.g. enum Region { EU, US, UK }, and switch over it"));
    }

    @Test
    void publicClassesUsedOnlyInTheirPackageShouldBePackagePrivate() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("OrderMapper", "package shop.orders;\n\npublic class OrderMapper {\n"
                        + "    public String map(String value) {\n        return value;\n    }\n}\n")
                .add(ORDERS, "package shop.orders;\n\npublic class Orders {\n"
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

    @Test
    void classesOtherPackagesUseForConstantsOrNestedTypesStayPublic() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Limits", """
                        package shop.orders;

                        public final class Limits {
                            public static final int MAX_ITEMS = 10;

                            private Limits() {
                            }

                            public static boolean tooMany(int items) {
                                return items > MAX_ITEMS;
                            }
                        }
                        """)
                .add(ORDER, """
                        package shop.orders;

                        public record Order(Order.Status status) {
                            public enum Status { OPEN, PAID }
                        }
                        """)
                .add(ORDERS, """
                        package shop.orders;

                        public class Orders {
                            public Order place(int items) {
                                return new Order(Limits.tooMany(items) ? Order.Status.OPEN : Order.Status.PAID);
                            }
                        }
                        """)
                .add("Controller", """
                        package shop.web;

                        public class Controller {
                            public String show(shop.orders.Orders orders) {
                                return orders.place(shop.orders.Limits.MAX_ITEMS) + " " + shop.orders.Order.Status.PAID;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.api().minimalVisibility().evaluate()).isEmpty();
    }
}
