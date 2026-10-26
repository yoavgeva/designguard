package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;

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
class TransactionIoRuleTest {

    private static final String RUNTIME = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n";
    private static final String HELD = " inside a transaction, which leaves the database: a rollback can't take it back, and the "
            + "transaction holds its connection while it waits";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void transactionsCallNothingOutsideTheDatabase() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Propagation", "package org.springframework.transaction.annotation;\n\n"
                        + "public enum Propagation {\n    REQUIRED, NOT_SUPPORTED, NEVER\n}\n")
                .add("Transactional", "package org.springframework.transaction.annotation;\n\n" + RUNTIME
                        + "public @interface Transactional {\n    Propagation propagation() default Propagation.REQUIRED;\n}\n")
                .add("Repository", "package org.springframework.data.repository;\n\npublic interface Repository<T, ID> {\n}\n")
                .add("RestOperations", "package org.springframework.web.client;\n\npublic interface RestOperations {\n"
                        + "    <T> T postForObject(String url, Object request, Class<T> type);\n}\n")
                .add("MailSender", "package org.springframework.mail;\n\npublic interface MailSender {\n"
                        + "    void send(Object message);\n}\n")
                .add("OrderRepository", "package shop;\n\npublic interface OrderRepository "
                        + "extends org.springframework.data.repository.Repository<String, Long> {\n"
                        + "    String save(String order);\n}\n")
                .add("PaymentGateway", """
                        package shop;

                        import org.springframework.web.client.RestOperations;

                        public class PaymentGateway {
                            private final RestOperations rest;

                            public PaymentGateway(RestOperations rest) {
                                this.rest = rest;
                            }

                            public String charge(String order) {
                                return rest.postForObject("https://pay.example.com", order, String.class);
                            }
                        }
                        """)
                .add("OrderService", """
                        package shop;

                        import org.springframework.mail.MailSender;
                        import org.springframework.transaction.annotation.Propagation;
                        import org.springframework.transaction.annotation.Transactional;

                        public class OrderService {
                            private final OrderRepository orders;
                            private final PaymentGateway payments;
                            private final MailSender mail;

                            public OrderService(OrderRepository orders, PaymentGateway payments, MailSender mail) {
                                this.orders = orders;
                                this.payments = payments;
                                this.mail = mail;
                            }

                            @Transactional
                            public String place(String order) {
                                String saved = orders.save(order);
                                payments.charge(saved);
                                return saved;
                            }

                            @Transactional
                            public void confirm(String order) {
                                orders.save(order);
                                mail.send(order);
                            }

                            @Transactional
                            public String save(String order) {
                                return orders.save(order);
                            }

                            public String placeThenCharge(String order) {
                                String saved = save(order);
                                payments.charge(saved);
                                return saved;
                            }

                            @Transactional(propagation = Propagation.NOT_SUPPORTED)
                            public String quote(String order) {
                                return payments.charge(order);
                            }
                        }
                        """)
                .add("Billing", """
                        package shop;

                        import org.springframework.transaction.annotation.Transactional;

                        @Transactional
                        public class Billing {
                            private final PaymentGateway payments;

                            public Billing(PaymentGateway payments) {
                                this.payments = payments;
                            }

                            public String bill(String order) {
                                return payments.charge(order);
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.dependencies().noIoInTransactions().evaluate())
                .extracting(Violation::message)
                .containsExactly(
                        "Billing.bill() calls PaymentGateway.charge()" + HELD,
                        "OrderService.place() calls PaymentGateway.charge()" + HELD,
                        "OrderService.confirm() calls MailSender.send()" + HELD);
        assertThat(guard.dependencies().noIoInTransactions().evaluate().get(0).fix()).isEqualTo("make the call after the "
                + "commit, as a @TransactionalEventListener(phase = AFTER_COMMIT) does, or record it in an outbox table in "
                + "the transaction and send it after");
    }
}
