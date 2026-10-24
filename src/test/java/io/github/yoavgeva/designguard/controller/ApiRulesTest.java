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

                            public static java.util.stream.Stream<String> stream(Optional<String> value) {
                                return value.stream();
                            }

                            public static String orElseNull(Optional<String> value) {
                                return value.orElse(null);
                            }

                            public static void print(Optional<String> value) {
                                System.out.println(value);
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
                        "return Optional.empty() instead of null"),
                tuple("Order.print() takes an Optional parameter",
                        "take the value itself, or offer an overload without it"));
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

                            public Counter getCopy() {
                                Counter copy = new Counter();
                                copy.count = count;
                                copy.dirty = dirty;
                                return copy;
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
    void lazyCachesChangeNothingButHelpersCollectionsAndAtomicsDo() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Catalog", """
                        package shop;

                        import java.util.ArrayList;
                        import java.util.HashMap;
                        import java.util.List;
                        import java.util.Map;
                        import java.util.concurrent.atomic.AtomicInteger;

                        public class Catalog {
                            private final Map<String, Integer> lengths = new HashMap<>();
                            private final List<String> seen = new ArrayList<>();
                            private final AtomicInteger reads = new AtomicInteger();
                            private String typeName;
                            private boolean lob;
                            private String primaryKey;
                            private boolean primaryKeyLoaded;
                            private String connection;
                            private String cached;
                            private String loaded;
                            private int count;
                            private boolean dirty;

                            public String getTypeName() {
                                if (typeName == null) {
                                    typeName = "varchar";
                                    lob = false;
                                }
                                return typeName + lob;
                            }

                            public String getPrimaryKey() {
                                if (!primaryKeyLoaded) {
                                    primaryKey = "id";
                                    primaryKeyLoaded = true;
                                }
                                return primaryKey;
                            }

                            public String getConnection() {
                                if (!hasConnection()) {
                                    connection = "jdbc";
                                }
                                return connection;
                            }

                            public boolean hasConnection() {
                                return connection != null;
                            }

                            public String getCached() {
                                if (cached != null) {
                                    return cached;
                                }
                                cached = "value";
                                return cached;
                            }

                            public int getLength(String key) {
                                Integer length = lengths.get(key);
                                if (length == null) {
                                    length = key.length();
                                    lengths.put(key, length);
                                }
                                return length;
                            }

                            public String getLoaded() {
                                ensureLoaded();
                                return loaded;
                            }

                            private void ensureLoaded() {
                                if (loaded == null) {
                                    loaded = "x";
                                }
                            }

                            public List<String> getSeen() {
                                seen.add("read");
                                return seen;
                            }

                            public int getReads() {
                                return reads.incrementAndGet();
                            }

                            public int getTotal() {
                                reset();
                                return count;
                            }

                            private void reset() {
                                count = 0;
                            }

                            public boolean isFresh() {
                                if (dirty) {
                                    dirty = false;
                                    return true;
                                }
                                return false;
                            }
                        }
                        """)
                .add("Lookups", """
                        package shop;

                        import java.util.Map;
                        import java.util.concurrent.ConcurrentHashMap;
                        import java.util.concurrent.atomic.AtomicInteger;

                        public class Lookups implements Map.Entry<String, String> {
                            private final Map<String, Integer> ids = new ConcurrentHashMap<>();
                            private final AtomicInteger next = new AtomicInteger();
                            private String delegate;
                            private int suffixIndex = -1;
                            private boolean inferring;
                            private int reads;

                            public String getDelegate() {
                                String made = delegate;
                                return made != null ? made : (delegate = "made");
                            }

                            public int getSuffixIndex() {
                                int index = suffixIndex;
                                if (index == -1) {
                                    suffixIndex = index = 3;
                                }
                                return index;
                            }

                            public int getId(String key) {
                                Integer id = ids.get(key);
                                if (id == null) {
                                    id = next.getAndIncrement();
                                    ids.putIfAbsent(key, id);
                                }
                                return id;
                            }

                            public String getInferred() {
                                inferring = true;
                                String value = "v" + reads;
                                inferring = false;
                                return value;
                            }

                            public void getReady() {
                                reads = 0;
                            }

                            public Map<String, Integer> getDefaults() {
                                if (ids.isEmpty()) {
                                    ids.put("default", 0);
                                }
                                return ids;
                            }

                            public int getCleansed() {
                                if (ids.size() == suffixIndex) {
                                    return suffixIndex;
                                }
                                suffixIndex = ids.size();
                                return suffixIndex;
                            }

                            @Override
                            public String getKey() {
                                reads++;
                                return "key";
                            }

                            @Override
                            public String getValue() {
                                return "value" + inferring;
                            }

                            @Override
                            public String setValue(String value) {
                                return value;
                            }
                        }
                        """)
                .add("Tokens", """
                        package shop;

                        public class Tokens {
                            private final String pattern = "yyyy";
                            private int position;

                            public boolean hasNext() {
                                position++;
                                return position < 3;
                            }

                            public String getNextToken() {
                                if (position >= pattern.length()) {
                                    return null;
                                }
                                return advance();
                            }

                            private String advance() {
                                position++;
                                return pattern.substring(position - 1, position);
                            }
                        }
                        """)
                .add("Letters", """
                        package shop;

                        import java.util.Iterator;

                        public class Letters implements Iterator<String> {
                            private int index;

                            @Override
                            public boolean hasNext() {
                                index++;
                                return index < 3;
                            }

                            @Override
                            public String next() {
                                return "a" + index;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.api().accessorsChangeNothing().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(79, "Catalog.getSeen() changes the field 'seen'"),
                        tuple(84, "Catalog.getReads() changes the field 'reads'"),
                        tuple(88, "Catalog.getTotal() changes the field 'count' through reset()"),
                        tuple(98, "Catalog.isFresh() changes the field 'dirty'"),
                        tuple(16, "Tokens.getNextToken() changes the field 'position' through advance()"));
    }

    @Test
    void fieldsHelpersSetAndClearAroundTheWorkAreNotChanges() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Session", "package shop;\n\npublic class Session {\n    public String mode = \"normal\";\n}\n")
                .add("Query", """
                        package shop;

                        public class Query {
                            private final Session session;
                            private String savedMode;
                            private boolean running;
                            private String last;

                            public Query(Session session) {
                                this.session = session;
                            }

                            public String getResult() {
                                before();
                                try {
                                    return "rows" + running;
                                } finally {
                                    after();
                                }
                            }

                            private void before() {
                                savedMode = session.mode;
                                session.mode = "query";
                                running = true;
                            }

                            private void after() {
                                if (savedMode != null) {
                                    session.mode = savedMode;
                                    savedMode = null;
                                }
                                running = false;
                            }

                            public String getLast() {
                                last = session.mode;
                                return last;
                            }

                            public String getCleared() {
                                forget();
                                return "cleared";
                            }

                            private void forget() {
                                last = null;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.api().accessorsChangeNothing().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(37, "Query.getLast() changes the field 'last'"),
                        tuple(42, "Query.getCleared() changes the field 'last' through forget()"));
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

                            public static Delivery delivery(String text) {
                                return switch (text) {
                                    case "express" -> Delivery.EXPRESS;
                                    case "pickup" -> Delivery.PICKUP;
                                    case "standard" -> Delivery.STANDARD;
                                    default -> throw new IllegalArgumentException(text);
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

                            public int weight(String unit) {
                                if (java.util.Objects.equals(unit, "kg")) {
                                    return 1000;
                                } else if (java.util.Objects.equals("g", unit)) {
                                    return 1;
                                } else if (java.util.Objects.equals(unit, "lb")) {
                                    return 453;
                                }
                                return 0;
                            }

                            public String sqlType(String type) {
                                switch (type) {
                                    case "int":
                                        return "integer";
                                    case "long":
                                        return "bigint";
                                    case "boolean":
                                        return "bit";
                                    default:
                                        return type;
                                }
                            }

                            public int setting(String key) {
                                if (key.equals("shop.show_sql")) {
                                    return 1;
                                } else if (key.equals("shop.format_sql")) {
                                    return 2;
                                } else if (key.equals("shop.use_comments")) {
                                    return 3;
                                }
                                return 0;
                            }

                            public int retry(String sqlState) {
                                switch (sqlState) {
                                    case "40P01":
                                        return 1;
                                    case "55P03":
                                        return 2;
                                    case "57014":
                                        return 3;
                                    default:
                                        return 0;
                                }
                            }

                            public int invoke(String method) {
                                switch (method) {
                                    case "close":
                                        return 1;
                                    case "isClosed":
                                        return 2;
                                    case "toString":
                                        return 3;
                                    default:
                                        return 0;
                                }
                            }

                            public int operator(String token) {
                                switch (token) {
                                    case "+":
                                        return 1;
                                    case "-":
                                        return 2;
                                    case "*":
                                        return 3;
                                    default:
                                        return 0;
                                }
                            }

                            public int depth(String token) {
                                switch (token) {
                                    case "(":
                                        return 1;
                                    case ")":
                                        return -1;
                                    case "select":
                                    case "from":
                                        return 0;
                                    default:
                                        return 2;
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
                                "replace the strings with an enum, e.g. enum Region { EU, US, UK }, and switch over it"),
                        tuple(59, "Fees.weight() compares unit with the strings \"kg\", \"g\" and \"lb\": make them an enum",
                                "replace the strings with an enum, e.g. enum Unit { KG, G, LB }, and switch over it"));
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
