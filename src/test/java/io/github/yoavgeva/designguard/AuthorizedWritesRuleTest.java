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
class AuthorizedWritesRuleTest {

    private static final String RUNTIME = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n";
    private static final String WEB = "package org.springframework.web.bind.annotation;\n\n" + RUNTIME;

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void handlersThatWriteCheckWhoIsAsking() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("RestController", WEB + "public @interface RestController {\n}\n")
                .add("PostMapping", WEB + "public @interface PostMapping {\n    String[] value() default {};\n}\n")
                .add("GetMapping", WEB + "public @interface GetMapping {\n    String[] value() default {};\n}\n")
                .add("DeleteMapping", WEB + "public @interface DeleteMapping {\n    String[] value() default {};\n}\n")
                .add("PathVariable", WEB + "public @interface PathVariable {\n}\n")
                .add("PreAuthorize", "package org.springframework.security.access.prepost;\n\n" + RUNTIME
                        + "public @interface PreAuthorize {\n    String value();\n}\n")
                .add("AuthenticationPrincipal", "package org.springframework.security.core.annotation;\n\n" + RUNTIME
                        + "public @interface AuthenticationPrincipal {\n}\n")
                .add("Repository", "package org.springframework.data.repository;\n\npublic interface Repository<T, ID> {\n}\n")
                .add("OrderRepository", "package shop;\n\npublic interface OrderRepository "
                        + "extends org.springframework.data.repository.Repository<String, String> {\n"
                        + "    String save(String order);\n\n    void deleteById(String id);\n\n    String findById(String id);\n}\n")
                .add("Query", "package jakarta.persistence;\n\npublic interface Query {\n"
                        + "    java.util.List<?> getResultList();\n}\n")
                .add("EntityManager", "package jakarta.persistence;\n\npublic interface EntityManager {\n"
                        + "    Query createNativeQuery(String sql);\n}\n")
                .add("SecurityHelper", "package shop;\n\npublic final class SecurityHelper {\n"
                        + "    private SecurityHelper() {\n    }\n\n"
                        + "    public static String getCurrentUsername() {\n        return \"me\";\n    }\n}\n")
                .add("Search", """
                        package shop;

                        public class Search {
                            private final jakarta.persistence.EntityManager entities;

                            public Search(jakarta.persistence.EntityManager entities) {
                                this.entities = entities;
                            }

                            public java.util.List<?> orders(String name) {
                                return entities.createNativeQuery("select * from orders where name = ?").getResultList();
                            }
                        }
                        """)
                .add("Orders", """
                        package shop;

                        import org.springframework.security.access.prepost.PreAuthorize;

                        public class Orders {
                            private final OrderRepository repository;

                            public Orders(OrderRepository repository) {
                                this.repository = repository;
                            }

                            public String cancel(String id) {
                                return repository.save(repository.findById(id) + ":cancelled");
                            }

                            public String cancelFor(String user, String id) {
                                return repository.save(user + ":" + id + ":cancelled");
                            }

                            @PreAuthorize("hasRole('SUPPORT')")
                            public String refund(String id) {
                                return repository.save(id + ":refunded");
                            }

                            public String find(String id) {
                                return repository.findById(id);
                            }
                        }
                        """)
                .add("OrderController", """
                        package shop;

                        import java.security.Principal;
                        import org.springframework.security.access.prepost.PreAuthorize;
                        import org.springframework.security.core.annotation.AuthenticationPrincipal;
                        import org.springframework.web.bind.annotation.DeleteMapping;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.PostMapping;
                        import org.springframework.web.bind.annotation.RestController;

                        @RestController
                        public class OrderController {
                            private final Orders orders;
                            private final OrderRepository repository;

                            public OrderController(Orders orders, OrderRepository repository) {
                                this.orders = orders;
                                this.repository = repository;
                            }

                            @PostMapping("/orders/{id}/cancel")
                            public String cancel(@PathVariable String id) {
                                return orders.cancel(id);
                            }

                            @PostMapping("/my/orders/{id}/cancel")
                            public String cancelOwn(@AuthenticationPrincipal String user, @PathVariable String id) {
                                return orders.cancelFor(user, id);
                            }

                            @PostMapping("/orders/{id}/archive")
                            public String archive(Principal principal, @PathVariable String id) {
                                return orders.cancelFor(principal.getName(), id);
                            }

                            @DeleteMapping("/orders/{id}")
                            @PreAuthorize("hasRole('ADMIN')")
                            public void delete(@PathVariable String id) {
                                repository.deleteById(id);
                            }

                            @PostMapping("/orders/{id}/refund")
                            public String refund(@PathVariable String id) {
                                return orders.refund(id);
                            }

                            @GetMapping("/orders/{id}")
                            public String show(@PathVariable String id) {
                                return orders.find(id);
                            }

                            @PostMapping("/mine/{id}/cancel")
                            public String cancelMine(@PathVariable String id) {
                                return orders.cancelFor(SecurityHelper.getCurrentUsername(), id);
                            }

                            @PostMapping("/orders/search")
                            public java.util.List<?> search(String name, Search search) {
                                return search.orders(name);
                            }
                        }
                        """)
                .add("AdminController", """
                        package shop;

                        import org.springframework.security.access.prepost.PreAuthorize;
                        import org.springframework.web.bind.annotation.DeleteMapping;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.RestController;

                        @RestController
                        @PreAuthorize("hasRole('ADMIN')")
                        public class AdminController {
                            private final OrderRepository repository;

                            public AdminController(OrderRepository repository) {
                                this.repository = repository;
                            }

                            @DeleteMapping("/admin/orders/{id}")
                            public void purge(@PathVariable String id) {
                                repository.deleteById(id);
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.guardrails().writesAreAuthorized().evaluate())
                .extracting(Violation::line, Violation::message, Violation::fix)
                .containsExactly(tuple(24, "OrderController.cancel() writes to the database through Orders.cancel() with "
                                + "nothing checking that the user asking may",
                        "take the current user, as an @AuthenticationPrincipal parameter, and scope the change to it, or "
                                + "check permissions with @PreAuthorize"));
    }
}
