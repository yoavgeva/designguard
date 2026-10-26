package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FloatingPointMoneyRuleTest {

    private static final String DRIFT = " as a floating-point number, which can't hold most decimal amounts exactly, so sums "
            + "and comparisons drift by fractions of a cent";

    @TempDir
    private Path directory;

    @Test
    void moneyIsntStoredAsFloatingPoint() {
        DesignGuard guard = new CompiledProject(directory)
                .add("Invoice", """
                        package shop;

                        public class Invoice {
                            private double totalPrice;
                            private Float shippingFee;
                            private double taxRate;
                            private long amountCents;
                            private java.math.BigDecimal price;
                            private double distanceKm;
                            private static final double DEFAULT_COST = 0.0;
                        }
                        """)
                .add("Refund", """
                        package shop;

                        public record Refund(String orderId, double amount, double feePercent) {
                        }
                        """)
                .guard("shop");

        assertThat(guard.data().noFloatingPointMoney().evaluate())
                .extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(4, "Invoice stores totalPrice" + DRIFT),
                        tuple(5, "Invoice stores shippingFee" + DRIFT),
                        tuple(3, "Refund stores amount" + DRIFT));
        assertThat(guard.data().noFloatingPointMoney().evaluate().get(0).fix())
                .isEqualTo("store it as a BigDecimal, or as a long count of cents");
    }
}
