package io.github.yoavgeva.designguard.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextReportTest {

    private final TextReport report = new TextReport();

    @Test
    void formatsAViolationAsOneActionableLine() {
        var violation = new Violation(
                "interface-policy",
                Violation.Severity.ERROR,
                "src/main/java/shop/OrderService.java",
                14,
                "OrderService (domain) takes concrete StripeClient (infra) as a constructor parameter",
                "depend on a PaymentGateway interface owned by the domain");

        assertThat(report.line(violation)).isEqualTo(
                "src/main/java/shop/OrderService.java:14: error [interface-policy] "
                        + "OrderService (domain) takes concrete StripeClient (infra) as a constructor parameter"
                        + " -> fix: depend on a PaymentGateway interface owned by the domain");
    }

    @Test
    void omitsTheLineNumberWhenUnknown() {
        var violation = new Violation(
                "api-break", Violation.Severity.WARNING, "src/main/java/shop/Cart.java", 0,
                "public method Cart.total() was removed", "deprecate it first, remove it in a later version");

        assertThat(report.line(violation)).startsWith("src/main/java/shop/Cart.java: warning [api-break] ");
    }

    @Test
    void rendersOneLinePerViolation() {
        var first = new Violation("a", Violation.Severity.ERROR, "A.java", 1, "first", "fix a");
        var second = new Violation("b", Violation.Severity.WARNING, "B.java", 2, "second", "fix b");

        assertThat(report.render(List.of(first, second)).lines()).containsExactly(
                "A.java:1: error [a] first -> fix: fix a",
                "B.java:2: warning [b] second -> fix: fix b");
    }
}
