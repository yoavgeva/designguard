package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
class AuthorizedReadsRuleTest {

    private static final String RUNTIME = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n";
    private static final String WEB = "package org.springframework.web.bind.annotation;\n\n" + RUNTIME;
    private static final String UNCHECKED = ": nothing checks that the user asking may see it";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void handlersThatReadByTheRequestsIdCheckWhoIsAsking() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("RestController", WEB + "public @interface RestController {\n}\n")
                .add("GetMapping", WEB + "public @interface GetMapping {\n    String[] value() default {};\n}\n")
                .add("PathVariable", WEB + "public @interface PathVariable {\n}\n")
                .add("RequestParam", WEB + "public @interface RequestParam {\n}\n")
                .add("PreAuthorize", "package org.springframework.security.access.prepost;\n\n" + RUNTIME
                        + "public @interface PreAuthorize {\n    String value();\n}\n")
                .add("AuthenticationPrincipal", "package org.springframework.security.core.annotation;\n\n" + RUNTIME
                        + "public @interface AuthenticationPrincipal {\n}\n")
                .add("Repository", "package org.springframework.data.repository;\n\npublic interface Repository<T, ID> {\n}\n")
                .add("CrudRepository", "package org.springframework.data.repository;\n\n"
                        + "public interface CrudRepository<T, ID> extends Repository<T, ID> {\n"
                        + "    java.util.Optional<T> findById(ID id);\n}\n")
                .add("Order", "package shop;\n\npublic class Order {\n}\n")
                .add("OrderRepository", "package shop;\n\npublic interface OrderRepository extends "
                        + "org.springframework.data.repository.CrudRepository<Order, Long> {\n"
                        + "    java.util.Optional<Order> findByIdAndOwner(Long id, String owner);\n}\n")
                .add("OrderService", "package shop;\n\npublic interface OrderService {\n    Order find(Long id);\n\n"
                        + "    Order findOwned(Long id, String owner);\n\n    Order latest();\n}\n")
                .add("OrderServiceImpl", """
                        package shop;

                        public class OrderServiceImpl implements OrderService {
                            private static final Long LATEST = 1L;
                            private final OrderRepository orders;

                            public OrderServiceImpl(OrderRepository orders) {
                                this.orders = orders;
                            }

                            @Override
                            public Order find(Long id) {
                                return orders.findById(id).orElseThrow();
                            }

                            @Override
                            public Order findOwned(Long id, String owner) {
                                return orders.findByIdAndOwner(id, owner).orElseThrow();
                            }

                            @Override
                            public Order latest() {
                                return orders.findById(LATEST).orElseThrow();
                            }
                        }
                        """)
                .add("OrderController", """
                        package shop;

                        import org.springframework.security.access.prepost.PreAuthorize;
                        import org.springframework.security.core.annotation.AuthenticationPrincipal;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.RequestParam;
                        import org.springframework.web.bind.annotation.RestController;

                        @RestController
                        public class OrderController {
                            private final OrderService orders;

                            public OrderController(OrderService orders) {
                                this.orders = orders;
                            }

                            @GetMapping("/orders/{id}")
                            public Order get(@PathVariable Long id) {
                                return orders.find(id);
                            }

                            @GetMapping("/orders/{id}/mine")
                            public Order mine(@PathVariable Long id, @AuthenticationPrincipal Object user) {
                                return orders.findOwned(id, user.toString());
                            }

                            @GetMapping("/admin/orders/{id}")
                            @PreAuthorize("hasRole('ADMIN')")
                            public Order admin(@PathVariable Long id) {
                                return orders.find(id);
                            }

                            @GetMapping("/orders/latest")
                            public Order latest() {
                                return orders.latest();
                            }

                            @GetMapping("/orders/by-number")
                            public Order byNumber(@RequestParam String number) {
                                return orders.find(Long.valueOf(number));
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.guardrails().readsAreAuthorized().evaluate())
                .extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(20, "OrderController.get() reads a record by the id the request sends through "
                                + "OrderService.find(), which calls OrderRepository.findById()" + UNCHECKED),
                        tuple(41, "OrderController.byNumber() reads a record by the id the request sends through "
                                + "OrderService.find(), which calls OrderRepository.findById()" + UNCHECKED));
        assertThat(guard.guardrails().readsAreAuthorized().evaluate().get(0).fix())
                .isEqualTo("take the current user, as an @AuthenticationPrincipal parameter, and scope the query to it, "
                        + "or check permissions with @PreAuthorize");
    }
}
