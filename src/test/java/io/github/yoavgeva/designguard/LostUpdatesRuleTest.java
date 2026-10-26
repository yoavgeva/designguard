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
class LostUpdatesRuleTest {

    private static final String RUNTIME = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n";
    private static final String PERSISTENCE = "package jakarta.persistence;\n\n";
    private static final String LOST = " from its old value: a request that changes it between the read and this write is lost";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void entitiesArentUpdatedFromStaleValues() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Entity", PERSISTENCE + RUNTIME + "public @interface Entity {\n}\n")
                .add("Version", PERSISTENCE + RUNTIME + "public @interface Version {\n}\n")
                .add("LockModeType", PERSISTENCE + "public enum LockModeType {\n    PESSIMISTIC_WRITE\n}\n")
                .add("Transactional", "package org.springframework.transaction.annotation;\n\n" + RUNTIME
                        + "public @interface Transactional {\n}\n")
                .add("Lock", "package org.springframework.data.jpa.repository;\n\n" + RUNTIME
                        + "public @interface Lock {\n    jakarta.persistence.LockModeType value();\n}\n")
                .add("Repository", "package org.springframework.data.repository;\n\npublic interface Repository<T, ID> {\n}\n")
                .add("Product", """
                        package shop;

                        @jakarta.persistence.Entity
                        public class Product {
                            private Long id;
                            private int stock;
                            private String name;

                            public int getStock() {
                                return stock;
                            }

                            public void setStock(int stock) {
                                this.stock = stock;
                            }

                            public void decreaseStock(int quantity) {
                                this.stock -= quantity;
                            }

                            public void rename(String name) {
                                this.name = name;
                            }
                        }
                        """)
                .add("Account", """
                        package shop;

                        @jakarta.persistence.Entity
                        public class Account {
                            @jakarta.persistence.Version
                            private long version;
                            private java.math.BigDecimal balance;

                            public java.math.BigDecimal getBalance() {
                                return balance;
                            }

                            public void setBalance(java.math.BigDecimal balance) {
                                this.balance = balance;
                            }
                        }
                        """)
                .add("ProductRepository", """
                        package shop;

                        import jakarta.persistence.LockModeType;
                        import org.springframework.data.jpa.repository.Lock;

                        public interface ProductRepository extends org.springframework.data.repository.Repository<Product, Long> {
                            Product save(Product product);

                            Product findById(Long id);

                            @Lock(LockModeType.PESSIMISTIC_WRITE)
                            Product findLockedById(Long id);
                        }
                        """)
                .add("Inventory", """
                        package shop;

                        import org.springframework.transaction.annotation.Transactional;

                        public class Inventory {
                            private final ProductRepository products;

                            public Inventory(ProductRepository products) {
                                this.products = products;
                            }

                            @Transactional
                            public void sell(Long id, int quantity) {
                                Product product = products.findById(id);
                                product.setStock(product.getStock() - quantity);
                            }

                            @Transactional
                            public void sellLocked(Long id, int quantity) {
                                Product product = products.findLockedById(id);
                                product.setStock(product.getStock() - quantity);
                            }

                            @Transactional
                            public void ship(Long id, int quantity) {
                                products.findById(id).decreaseStock(quantity);
                            }

                            public void restock(Product product) {
                                product.setStock(10);
                                products.save(product);
                            }

                            @Transactional
                            public void credit(Account account, java.math.BigDecimal amount) {
                                account.setBalance(account.getBalance().add(amount));
                            }

                            public int preview(Product product) {
                                Product copy = new Product();
                                copy.setStock(product.getStock() - 1);
                                return copy.getStock();
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.data().noLostUpdates().evaluate())
                .extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(15, "Inventory.sell() sets Product.stock" + LOST),
                        tuple(26, "Inventory.ship() calls Product.decreaseStock(), which sets stock" + LOST));
        assertThat(guard.data().noLostUpdates().evaluate().get(0).fix())
                .isEqualTo("add a @Version field to Product, or change it in the database with an @Modifying UPDATE "
                        + "query, or read it with @Lock(LockModeType.PESSIMISTIC_WRITE)");
    }
}
