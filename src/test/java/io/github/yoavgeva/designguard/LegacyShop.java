package io.github.yoavgeva.designguard;


/** A shop in {@code shop.legacy} that breaks every rule at least once, for tests that hold of every rule. */
final class LegacyShop {

    private static final String RUNTIME = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n";

    private LegacyShop() {
    }

    /**
     * Adds the shop's sources and tests.
     *
     * @param project an empty project
     * @return the project, for chaining
     */
    static CompiledProject write(CompiledProject project) {
        return tests(misc(layers(project)));
    }

    /** Layers, MVC, hexagonal, onion, feature slices, stable dependencies and the boundaries. */
    private static CompiledProject layers(CompiledProject project) {
        return project
                .add("Service", "package org.springframework.stereotype;\n\n" + RUNTIME + "public @interface Service {\n}\n")
                .add("OrderController", "package shop.legacy.web;\n\npublic class OrderController {\n}\n")
                .add("OrderRepository", "package shop.legacy.persistence;\n\npublic class OrderRepository {\n"
                        + "    public String notify(shop.legacy.web.OrderController controller) {\n"
                        + "        return controller.toString();\n    }\n}\n")
                .add("Entity", "package shop.legacy.persistence;\n\n" + RUNTIME + "public @interface Entity {\n}\n")
                .add("OrderEntity", "package shop.legacy.persistence;\n\n@Entity\npublic class OrderEntity {\n}\n")
                .add("OrderPage", "package shop.legacy.web;\n\npublic class OrderPage {\n"
                        + "    public String render(shop.legacy.persistence.OrderEntity entity) {\n"
                        + "        return entity.toString();\n    }\n}\n")
                .add("CheckoutPage", "package shop.legacy.web;\n\npublic class CheckoutPage {\n"
                        + "    public String show(int items, boolean member) {\n        if (items == 0) {\n            return \"empty\";\n"
                        + "        }\n        if (member) {\n            return \"member\";\n        }\n"
                        + "        return items > 10 ? \"bulk\" : \"regular\";\n    }\n}\n")
                .add("InvoiceController", "package shop.legacy.controller;\n\npublic class InvoiceController {\n}\n")
                .add("Invoice", "package shop.legacy.model;\n\npublic class Invoice {\n"
                        + "    public String link(shop.legacy.controller.InvoiceController controller) {\n"
                        + "        return controller.toString();\n    }\n}\n")
                .add("PlaceOrder", "package shop.legacy.application;\n\npublic class PlaceOrder {\n}\n")
                .add("Price", "package shop.legacy.domain.model;\n\npublic class Price {\n"
                        + "    public String quote(shop.legacy.application.PlaceOrder useCase) {\n"
                        + "        return useCase.toString();\n    }\n}\n")
                .add("Pricing", "package shop.legacy.domain;\n\n@org.springframework.stereotype.Service\n"
                        + "public class Pricing {\n}\n")
                .add("OrderStore", "package shop.legacy.domain;\n\npublic interface OrderStore {\n"
                        + "    String load(String id) throws java.sql.SQLException;\n}\n")
                .add("Customer", "package shop.legacy.domain;\n\npublic record Customer(String customerId) {\n}\n")
                .add("OrderMath", "package shop.legacy.orders.internal;\n\npublic class OrderMath {\n"
                        + "    public int sum(int a, int b) {\n        return a + b;\n    }\n}\n")
                .add("Biller", "package shop.legacy.billing;\n\npublic class Biller {\n"
                        + "    public int bill(shop.legacy.orders.internal.OrderMath math) {\n        return math.sum(1, 2);\n"
                        + "    }\n}\n")
                .add("Money", "package shop.legacy.core;\n\npublic record Money(long cents) {\n}\n")
                .add("Audit", "package shop.legacy.core;\n\npublic class Audit {\n"
                        + "    public String log(shop.legacy.reports.Report report) {\n        return report.toString();\n    }\n}\n")
                .add("Charge", "package shop.legacy.billing;\n\npublic class Charge {\n"
                        + "    public long total(shop.legacy.core.Money money) {\n        return money.cents();\n    }\n}\n")
                .add("Report", "package shop.legacy.reports;\n\npublic class Report {\n"
                        + "    public String render(shop.legacy.billing.Charge charge, shop.legacy.core.Money money) {\n"
                        + "        return charge.total(money) + \"\";\n    }\n}\n");
    }

    /** Services, values, effects, types and APIs that break the dependency, type, API and guardrail rules. */
    private static CompiledProject misc(CompiledProject project) {
        return project
                .add("StripeClient", """
                        package shop.legacy.pay;

                        public class StripeClient {
                            private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

                            public String charge(long cents) {
                                return http.toString() + System.getenv("STRIPE_KEY") + cents;
                            }

                            public String fee(long cents) {
                                return java.sql.DriverManager.getLoginTimeout() + " " + cents;
                            }
                        }
                        """)
                .add("Checkout", """
                        package shop.legacy.pay;

                        public class Checkout {
                            private final StripeClient client = new StripeClient();

                            public String pay() {
                                return client.charge(1) + new StripeClient().charge(2);
                            }

                            public String payAll(java.util.List<Long> amounts) {
                                String receipts = "";
                                for (long amount : amounts) {
                                    receipts += client.fee(amount);
                                }
                                return receipts;
                            }
                        }
                        """)
                .add("PaymentLog", """
                        package shop.legacy.pay;

                        public class PaymentLog {
                            private int written;

                            public void write(String line) {
                                written++;
                                System.out.println(line + written);
                            }
                        }
                        """)
                .add("Effects", """
                        package shop.legacy.misc;

                        public class Effects {
                            public long now() {
                                return java.time.Instant.now().toEpochMilli();
                            }

                            public String id() {
                                return java.util.UUID.randomUUID().toString();
                            }

                            public String region() {
                                return System.getenv("REGION");
                            }

                            public void run(Runnable job) {
                                new Thread(job).start();
                            }
                        }
                        """)
                .add("Tally", """
                        package shop.legacy.misc;

                        public class Tally {
                            private int total;

                            public void add(int amount) {
                                total += amount;
                            }

                            public int getNext() {
                                return total++;
                            }

                            int recount() {
                                return total;
                            }
                        }
                        """)
                .add("Disk", """
                        package shop.legacy.misc;

                        public final class Disk {
                            private Disk() {
                            }

                            public static String read(java.nio.file.Path file) throws java.io.IOException {
                                return java.nio.file.Files.readString(file);
                            }
                        }
                        """)
                .add("Lookup", """
                        package shop.legacy.misc;

                        public class Lookup {
                            public Object find(String key) {
                                return key;
                            }

                            public String load(java.nio.file.Path file) throws java.io.IOException {
                                return Disk.read(file);
                            }
                        }
                        """)
                .add("Holder", "package shop.legacy.misc;\n\npublic class Holder {\n"
                        + "    private final java.util.Optional<String> name = java.util.Optional.empty();\n\n"
                        + "    public String name() {\n        return name.orElse(\"\");\n    }\n}\n")
                .add("Basket", "package shop.legacy.misc;\n\npublic record Basket(java.util.List<String> items) {\n}\n")
                .add("Point", """
                        package shop.legacy.misc;

                        public final class Point {
                            private final int x;
                            private final int y;

                            public Point(int x, int y) {
                                this.x = x;
                                this.y = y;
                            }

                            public int getX() {
                                return x;
                            }

                            public int getY() {
                                return y;
                            }
                        }
                        """)
                .add("Codes", "package shop.legacy.misc;\n\npublic final class Codes {\n"
                        + "    public static final int STATUS_OPEN = 0;\n    public static final int STATUS_PAID = 1;\n\n"
                        + "    private Codes() {\n    }\n}\n")
                .add("Printer", """
                        package shop.legacy.misc;

                        public class Printer {
                            // TODO handle locales
                            public String print(String text, boolean upper) {
                                if (upper) {
                                    return text.toUpperCase(java.util.Locale.ROOT);
                                }
                                return text;
                            }

                            public int width(String size) {
                                switch (size) {
                                    case "small":
                                        return 1;
                                    case "medium":
                                        return 2;
                                    case "large":
                                        return 3;
                                    default:
                                        return 0;
                                }
                            }

                            @SuppressWarnings("unchecked")
                            public java.util.List<String> names(Object list) {
                                return (java.util.List<String>) list;
                            }
                        }
                        """)
                .add("Keeper", "package shop.legacy.zoo;\n\npublic class Keeper {\n    private Zoo zoo;\n\n"
                        + "    public String zoo() {\n        return String.valueOf(zoo);\n    }\n}\n")
                .add("Zoo", "package shop.legacy.zoo;\n\npublic class Zoo {\n    private Keeper keeper;\n\n"
                        + "    public String keeper() {\n        return String.valueOf(keeper);\n    }\n}\n")
                .add("Feeding", "package shop.legacy.zoo;\n\npublic interface Feeding {\n    void feed(String animal);\n\n"
                        + "    void clean(String cage);\n\n    int count();\n\n    String report();\n\n    void water(String animal);\n\n"
                        + "    void weigh(String animal);\n\n    void groom(String animal);\n\n    void vaccinate(String animal);\n\n"
                        + "    void rest(String animal);\n\n    void play(String animal);\n}\n")
                .add("Feeder", "package shop.legacy.zoo;\n\npublic class Feeder {\n    public void feed(Feeding feeding) {\n"
                        + "        feeding.feed(\"cat\");\n    }\n}\n")
                .add("Auditor", "package shop.legacy.zoo;\n\npublic class Auditor {\n    public String audit(Feeding feeding) {\n"
                        + "        return feeding.report();\n    }\n}\n")
                .add("Animal", "package shop.legacy.zoo;\n\npublic interface Animal {\n}\n")
                .add("Cat", "package shop.legacy.zoo;\n\npublic record Cat() implements Animal {\n}\n")
                .add("Dog", "package shop.legacy.zoo;\n\npublic record Dog() implements Animal {\n}\n")
                .add("Sounds", """
                        package shop.legacy.zoo;

                        public class Sounds {
                            public String sound(Animal animal) {
                                if (animal instanceof Cat) {
                                    return "meow";
                                } else if (animal instanceof Dog) {
                                    return "woof";
                                }
                                return "";
                            }
                        }
                        """);
    }

    /** Tests that break the test guardrails. */
    private static CompiledProject tests(CompiledProject project) {
        return project
                .addTest("TallyTest", """
                        package shop.legacy.misc;

                        class TallyTest {
                            @org.junit.jupiter.api.Test
                            void recounts() {
                                org.assertj.core.api.Assertions.assertThat(new Tally().recount()).isZero();
                                org.assertj.core.api.Assertions.assertThat(java.time.LocalDate.now()).isNotNull();
                            }

                            @org.junit.jupiter.api.Test
                            void waitsForTheTally() throws InterruptedException {
                                Thread.sleep(10);
                                int start = new java.util.Random().nextInt(5) + System.getenv().size();
                                org.assertj.core.api.Assertions.assertThat(new Tally().recount() + start).isNotNegative();
                            }

                            @org.junit.jupiter.api.Test
                            void adds() {
                                new Tally().add(1);
                            }

                            @org.junit.jupiter.api.Test
                            void addsTwice() {
                                Tally tally = new Tally();
                                for (int round = 0; round < 2; round++) {
                                    tally.add(round);
                                    org.assertj.core.api.Assertions.assertThat(tally.getNext()).isPositive();
                                }
                            }

                            @org.junit.jupiter.api.Disabled
                            @org.junit.jupiter.api.Test
                            void later() {
                                org.assertj.core.api.Assertions.assertThat(new Tally().getNext()).isZero();
                            }
                        }
                        """)
                .addTest("LookupTest", """
                        package shop.legacy.misc;

                        import static org.mockito.Mockito.mock;
                        import static org.mockito.Mockito.verify;

                        class LookupTest {
                            private final Lookup lookup = mock(Lookup.class);

                            @org.junit.jupiter.api.Test
                            void finds() {
                                lookup.find("key");
                                verify(lookup).find("key");
                            }
                        }
                        """)
                .addTest("PointTest", """
                        package shop.legacy.misc;

                        class PointTest {
                            @org.junit.jupiter.api.Test
                            void moves() {
                                Point point = org.mockito.Mockito.mock(Point.class);
                                org.assertj.core.api.Assertions.assertThat(point.getX()).isZero();
                            }
                        }
                        """);
    }
}
