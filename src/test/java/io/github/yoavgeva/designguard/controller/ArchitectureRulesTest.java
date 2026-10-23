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
class ArchitectureRulesTest {

    private static final String SHOP = "shop";
    private static final String ORDER = "Order";
    private static final String WEB = "..web..";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void layeredForbidsUpwardDependencies() {
        DesignGuard guard = layeredShop().guard(SHOP);

        assertThat(guard.architecture().layered()
                .layer("Web", WEB).layer("Service", "..service..").layer("Persistence", "..persistence..")
                .evaluate()).singleElement().satisfies(violation -> {
                    assertThat(violation.rule()).isEqualTo("layered-architecture");
                    assertThat(violation.file()).endsWith("shop/persistence/OrderRepository.java");
                    assertThat(violation.message()).isEqualTo("OrderRepository (layer Persistence) depends on"
                            + " OrderController (layer Web), which Persistence may not use");
                    assertThat(violation.fix()).isEqualTo("Persistence may not depend on other layers;"
                            + " invert the dependency with an interface in Persistence");
                });
    }

    @Test
    void strictLayeringAlsoForbidsSkippingALayer() {
        DesignGuard guard = layeredShop().guard(SHOP);

        assertThat(guard.architecture().layered().strict()
                .layer("Web", WEB).layer("Service", "..service..").layer("Persistence", "..persistence..")
                .evaluate()).extracting(Violation::message).containsExactly(
                        "OrderRepository (layer Persistence) depends on OrderController (layer Web),"
                                + " which Persistence may not use",
                        "OrderController (layer Web) depends on OrderRepository (layer Persistence),"
                                + " which Web may not use");
    }

    @Test
    void layersSeeConstantsTheCompilerCopiesIntoTheCode() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Limits", "package shop.web;\n\npublic final class Limits {\n"
                        + "    public static final int PAGE_SIZE = 50;\n\n    private Limits() {\n    }\n}\n")
                .add("OrderRepository", """
                        package shop.persistence;

                        public class OrderRepository {
                            public int pageSize() {
                                return shop.web.Limits.PAGE_SIZE;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.architecture().layered().layer("Web", WEB).layer("Persistence", "..persistence..")
                .evaluate()).extracting(Violation::line, Violation::message).containsExactly(tuple(0,
                        "OrderRepository (layer Persistence) depends on Limits (layer Web), which Persistence may not use"));
    }

    @Test
    void classesThatDependOnEachOtherFormACycle() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add(ORDER, "package shop;\n\npublic class Order {\n    private final java.util.List<OrderLine> lines = java.util.List.of();\n\n"
                        + "    public int size() {\n        return lines.size();\n    }\n}\n")
                .add("OrderLine", "package shop;\n\npublic record OrderLine(Order order, int quantity) {\n}\n")
                .add("Invoice", "package shop.billing;\n\npublic class Invoice {\n"
                        + "    public String pay(Payment payment) {\n        return payment.toString();\n    }\n}\n")
                .add("Payment", "package shop.billing;\n\npublic class Payment {\n"
                        + "    public String receipt(shop.receipts.Receipt receipt) {\n        return receipt.toString();\n    }\n}\n")
                .add("Receipt", "package shop.receipts;\n\npublic class Receipt {\n"
                        + "    public String invoice(shop.billing.Invoice invoice) {\n        return invoice.toString();\n    }\n}\n")
                .add("Shape", "package shop.shapes;\n\npublic sealed interface Shape permits Circle {\n"
                        + "    static Shape unit() {\n        return new Circle(1);\n    }\n}\n")
                .add("Circle", "package shop.shapes;\n\npublic record Circle(double radius) implements Shape {\n}\n")
                .add("Cart", "package shop;\n\npublic class Cart {\n    public record Item(Cart cart) {\n    }\n\n"
                        + "    public Item first() {\n        return new Item(this);\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.architecture().classesHaveNoCycles().evaluate()).extracting(Violation::message).containsExactly(
                "Order and OrderLine depend on each other in a cycle",
                "Invoice, Payment and Receipt depend on each other in a cycle");
        assertThat(guard.architecture().classesHaveNoCycles().evaluate().get(0).fix()).isEqualTo("break the cycle:"
                + " move what they share into a class of its own, or let one side depend on an interface it owns");
    }

    @Test
    void adaptersTranslateAndDelegateWithoutDeciding() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("OrderController", """
                        package shop.web;

                        public class OrderController {
                            public String place(String type, int quantity) {
                                if (quantity <= 0) {
                                    return "invalid";
                                }
                                if (type.equals("express")) {
                                    return "fast";
                                } else if (type.equals("pickup")) {
                                    return "store";
                                }
                                return "standard";
                            }

                            public String get(String id) {
                                if (id.isBlank()) {
                                    return "missing";
                                }
                                return id;
                            }
                        }
                        """)
                .add("Pricing", """
                        package shop.domain;

                        public class Pricing {
                            public int fee(String type, int quantity) {
                                if (quantity > 10) {
                                    return 0;
                                }
                                if (type.equals("express")) {
                                    return 10;
                                }
                                return type.equals("pickup") ? 0 : 5;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.architecture().adaptersStayThin(WEB).evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(tuple(4, "OrderController.place() makes 3 decisions; an adapter should only translate"
                        + " and delegate"));
        assertThat(guard.architecture().adaptersStayThin(WEB).evaluate().get(0).fix())
                .isEqualTo("move the decisions into the application or domain layer, and call it from here");
    }

    @Test
    void mvcKeepsViewsAwayFromServices() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Cart", "package shop.model;\n\npublic record Cart(int items) {\n}\n")
                .add("CartService", "package shop.service;\n\npublic class CartService {\n"
                        + "    public shop.model.Cart load() {\n        return new shop.model.Cart(1);\n    }\n}\n")
                .add("CartView", "package shop.view;\n\npublic class CartView {\n"
                        + "    public String render(shop.service.CartService service) {\n"
                        + "        return service.load().toString();\n    }\n}\n")
                .add("CartController", "package shop.controller;\n\npublic class CartController {\n"
                        + "    public String show(shop.service.CartService service, shop.view.CartView view) {\n"
                        + "        return view.render(service);\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.architecture().mvc().evaluate()).extracting(Violation::message).containsExactly(
                "CartView (layer View) depends on CartService (layer Service), which View may not use");
        assertThat(guard.architecture().mvc().evaluate().get(0).fix())
                .isEqualTo("go through a layer View may use: Model");
    }

    @Test
    void hexagonalKeepsTheDomainPureAndAdaptersApart() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add(ORDER, "package shop.domain;\n\npublic class Order {\n"
                        + "    public String id(shop.application.PlaceOrder useCase) {\n        return useCase.toString();\n    }\n}\n")
                .add("PlaceOrder", "package shop.application;\n\npublic class PlaceOrder {\n"
                        + "    public shop.domain.Order place() {\n        return new shop.domain.Order();\n    }\n}\n")
                .add("OrderTable", "package shop.adapter.persistence;\n\npublic class OrderTable {\n}\n")
                .add("OrderEndpoint", "package shop.adapter.web;\n\npublic class OrderEndpoint {\n"
                        + "    public String get(shop.application.PlaceOrder useCase, shop.adapter.persistence.OrderTable table) {\n"
                        + "        return useCase.place() + table.toString();\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.architecture().hexagonal()
                .adapter("web", "..adapter.web..").adapter("persistence", "..adapter.persistence..")
                .evaluate()).extracting(Violation::message).containsExactly(
                        "OrderEndpoint (layer Adapter web) depends on OrderTable (layer Adapter persistence),"
                                + " which Adapter web may not use",
                        "Order (layer Domain) depends on PlaceOrder (layer Application), which Domain may not use");
    }

    @Test
    void onionRingsDependOnlyInwards() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Pricing", "package shop.domain.service;\n\npublic class Pricing {\n}\n")
                .add(ORDER, "package shop.domain.model;\n\npublic class Order {\n"
                        + "    public String priced(shop.domain.service.Pricing pricing) {\n        return pricing.toString();\n    }\n}\n")
                .add("Checkout", "package shop.application;\n\npublic class Checkout {\n"
                        + "    public String run(shop.adapter.db.OrderTable table, shop.domain.model.Order order) {\n"
                        + "        return table.toString() + order;\n    }\n}\n")
                .add("OrderTable", "package shop.adapter.db;\n\npublic class OrderTable {\n"
                        + "    public String save(shop.domain.model.Order order) {\n        return order.toString();\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.architecture().onion().adapter("db", "..adapter.db..").evaluate())
                .extracting(Violation::message).containsExactly(
                        "Checkout (layer Application service) depends on OrderTable (layer Adapter db),"
                                + " which Application service may not use",
                        "Order (layer Domain model) depends on Pricing (layer Domain service),"
                                + " which Domain model may not use");
    }

    @Test
    void featureSlicesUseEachOthersApiOnlyAndHaveNoCycles() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("StripeClient", "package shop.payments.internal;\n\npublic class StripeClient {\n}\n")
                .add("Payments", "package shop.payments;\n\npublic class Payments {\n"
                        + "    public String pay(shop.orders.Order order) {\n        return order.toString();\n    }\n}\n")
                .add(ORDER, "package shop.orders;\n\npublic class Order {\n}\n")
                .add("Checkout", "package shop.orders;\n\npublic class Checkout {\n"
                        + "    public String run(shop.payments.Payments payments, shop.payments.internal.StripeClient client) {\n"
                        + "        return payments.pay(new Order()) + client;\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.architecture().featureSlices().evaluate()).extracting(Violation::message).containsExactly(
                "Checkout (slice orders) uses StripeClient, which is internal to slice payments",
                "slices orders and payments depend on each other in a cycle");
    }

    private CompiledProject layeredShop() {
        return new CompiledProject(directory).release(release)
                .add("OrderController", "package shop.web;\n\npublic class OrderController {\n"
                        + "    public String show(shop.service.OrderService service, shop.persistence.OrderRepository repository) {\n"
                        + "        return service.toString() + repository;\n    }\n}\n")
                .add("OrderService", "package shop.service;\n\npublic class OrderService {\n"
                        + "    public String load(shop.persistence.OrderRepository repository) {\n"
                        + "        return repository.toString();\n    }\n}\n")
                .add("OrderRepository", "package shop.persistence;\n\npublic class OrderRepository {\n"
                        + "    public String notify(shop.web.OrderController controller) {\n"
                        + "        return controller.toString();\n    }\n}\n");
    }
}
