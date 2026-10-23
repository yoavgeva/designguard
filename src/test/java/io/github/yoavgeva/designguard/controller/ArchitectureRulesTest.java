package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void layeredForbidsUpwardDependencies() {
        DesignGuard guard = layeredShop().guard(SHOP);

        assertThat(guard.architecture().layered()
                .layer("Web", "..web..").layer("Service", "..service..").layer("Persistence", "..persistence..")
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
                .layer("Web", "..web..").layer("Service", "..service..").layer("Persistence", "..persistence..")
                .evaluate()).extracting(Violation::message).containsExactly(
                        "OrderRepository (layer Persistence) depends on OrderController (layer Web),"
                                + " which Persistence may not use",
                        "OrderController (layer Web) depends on OrderRepository (layer Persistence),"
                                + " which Web may not use");
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
                .add("Order", "package shop.domain;\n\npublic class Order {\n"
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
                .add("Order", "package shop.domain.model;\n\npublic class Order {\n"
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
                .add("Order", "package shop.orders;\n\npublic class Order {\n}\n")
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
