package io.github.yoavgeva.designguard.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonReportTest {

    private final JsonReport report = new JsonReport();

    @Test
    void writesEveryFieldOfEveryViolationInOrder() {
        Violation stub = new Violation("no-stubs", Violation.Severity.ERROR, "src/main/java/shop/Cart.java", 7,
                "Cart.total() throws \"not done\"", "finish it\tnow");
        Violation cycle = new Violation("class-cycles", Violation.Severity.WARNING, "src/main/java/shop/Order.java", 0,
                "Order and Line depend on each other in a cycle", "break the cycle").identifiedAs("Order, Line");

        assertThat(report.render(List.of(stub, cycle))).isEqualTo("""
                {
                  "violations": [
                    {
                      "rule": "no-stubs",
                      "severity": "error",
                      "file": "src/main/java/shop/Cart.java",
                      "line": 7,
                      "message": "Cart.total() throws \\"not done\\"",
                      "fix": "finish it\\tnow",
                      "identity": "Cart.total() throws \\"not done\\""
                    },
                    {
                      "rule": "class-cycles",
                      "severity": "warning",
                      "file": "src/main/java/shop/Order.java",
                      "line": 0,
                      "message": "Order and Line depend on each other in a cycle",
                      "fix": "break the cycle",
                      "identity": "Order, Line"
                    }
                  ]
                }
                """);
    }

    @Test
    void escapesControlCharactersAndBackslashes() {
        Violation odd = new Violation("r", Violation.Severity.ERROR, "C:\\shop\\A.java", 1, "line\nbreak\u0001", "f");

        assertThat(report.render(List.of(odd)))
                .contains("\"file\": \"C:\\\\shop\\\\A.java\"")
                .contains("\"message\": \"line\\nbreak\\u0001\"");
    }

    @Test
    void writesAnEmptyListWhenNothingIsWrong() {
        assertThat(report.render(List.of())).isEqualTo("""
                {
                  "violations": []
                }
                """);
    }
}
