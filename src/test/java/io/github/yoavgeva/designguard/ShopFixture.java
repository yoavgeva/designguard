package io.github.yoavgeva.designguard;

import java.nio.file.Path;

/**
 * A small shop: a payments slice with a network client and a port, and an orders slice using it well and badly.
 *
 * <ul>
 *   <li>{@code StripeClient}: service-like, because it does network I/O</li>
 *   <li>{@code PaymentGateway}: the port; {@code StripeGateway} implements it with a concrete client</li>
 *   <li>{@code OrderService}: holds the concrete client across slices (bad under every policy)</li>
 *   <li>{@code GoodOrderService}: holds the port (fine)</li>
 *   <li>{@code OrderAudit}: holds the concrete, service-like {@code OrderService} inside its slice</li>
 *   <li>{@code PriceCalculator}: uses the value {@code Money}</li>
 *   <li>{@code App}: wiring code, which may create and hold anything</li>
 * </ul>
 */
final class ShopFixture {

    private ShopFixture() {
    }

    static CompiledProject write(Path directory) {
        return new CompiledProject(directory)
                .add("StripeClient", """
                        package shop.payments;

                        public class StripeClient {
                            private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

                            public String charge(long cents) {
                                return http.toString() + cents;
                            }
                        }
                        """)
                .add("PaymentGateway", """
                        package shop.payments;

                        public interface PaymentGateway {
                            String charge(long cents);
                        }
                        """)
                .add("StripeGateway", """
                        package shop.payments;

                        public class StripeGateway implements PaymentGateway {
                            private final StripeClient client;

                            public StripeGateway(StripeClient client) {
                                this.client = client;
                            }

                            @Override
                            public String charge(long cents) {
                                return client.charge(cents);
                            }
                        }
                        """)
                .add("Money", """
                        package shop.orders;

                        public record Money(long cents) {
                        }
                        """)
                .add("OrderService", """
                        package shop.orders;

                        import shop.payments.StripeClient;

                        public class OrderService {
                            private final StripeClient client;

                            public OrderService(StripeClient client) {
                                this.client = client;
                            }

                            public String pay(Money money) {
                                return client.charge(money.cents());
                            }
                        }
                        """)
                .add("GoodOrderService", """
                        package shop.orders;

                        import shop.payments.PaymentGateway;

                        public class GoodOrderService {
                            private final PaymentGateway gateway;

                            public GoodOrderService(PaymentGateway gateway) {
                                this.gateway = gateway;
                            }

                            public String pay(Money money) {
                                return gateway.charge(money.cents());
                            }
                        }
                        """)
                .add("OrderAudit", """
                        package shop.orders;

                        public class OrderAudit {
                            private final OrderService orders;

                            public OrderAudit(OrderService orders) {
                                this.orders = orders;
                            }

                            public String audit() {
                                return orders.toString();
                            }
                        }
                        """)
                .add("PriceCalculator", """
                        package shop.orders;

                        public class PriceCalculator {
                            public long total(Money money) {
                                return money.cents() * 2;
                            }
                        }
                        """)
                .add("App", """
                        package shop;

                        import shop.orders.OrderService;
                        import shop.payments.StripeClient;

                        public final class App {
                            private final OrderService orders = new OrderService(new StripeClient());

                            public static void main(String[] args) {
                                new App().orders.toString();
                            }
                        }
                        """);
    }
}
