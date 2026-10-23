package io.github.yoavgeva.designguard.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import org.junit.jupiter.api.Test;

class SarifReportTest {

    private final SarifReport report = new SarifReport();

    @Test
    void writesASarifLogGitHubCodeScanningReads() {
        Violation stub = new Violation("no-stubs", Violation.Severity.ERROR, "src/main/java/shop/Cart.java", 7,
                "Cart.total() throws \"not done\"", "finish it");
        Violation cycle = new Violation("class-cycles", Violation.Severity.WARNING, "src/main/java/shop/Order.java", 0,
                "Order and Line depend on each other in a cycle", "break the cycle").identifiedAs("Order, Line");

        assertThat(report.render(List.of(stub, cycle))).isEqualTo("""
                {
                  "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
                  "version": "2.1.0",
                  "runs": [
                    {
                      "tool": {
                        "driver": {
                          "name": "designguard",
                          "informationUri": "https://github.com/yoavgeva/designguard",
                          "rules": [
                            {
                              "id": "class-cycles"
                            },
                            {
                              "id": "no-stubs"
                            }
                          ]
                        }
                      },
                      "results": [
                        {
                          "ruleId": "no-stubs",
                          "ruleIndex": 1,
                          "level": "error",
                          "message": {
                            "text": "Cart.total() throws \\"not done\\" -> fix: finish it"
                          },
                          "locations": [
                            {
                              "physicalLocation": {
                                "artifactLocation": {
                                  "uri": "src/main/java/shop/Cart.java"
                                },
                                "region": {
                                  "startLine": 7
                                }
                              }
                            }
                          ],
                          "partialFingerprints": {
                            "designguard/v1": "src/main/java/shop/Cart.java: [no-stubs] Cart.total() throws \\"not done\\""
                          }
                        },
                        {
                          "ruleId": "class-cycles",
                          "ruleIndex": 0,
                          "level": "warning",
                          "message": {
                            "text": "Order and Line depend on each other in a cycle -> fix: break the cycle"
                          },
                          "locations": [
                            {
                              "physicalLocation": {
                                "artifactLocation": {
                                  "uri": "src/main/java/shop/Order.java"
                                }
                              }
                            }
                          ],
                          "partialFingerprints": {
                            "designguard/v1": "src/main/java/shop/Order.java: [class-cycles] Order, Line"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
    }

    @Test
    void writesAbsolutePathsAsFileUrisAndEncodesRelativeOnes() {
        assertThat(report.render(List.of(at("/work/my shop/A.java"), at("C:\\work\\B.java"), at("src/my shop/C.java"))))
                .contains("\"uri\": \"file:///work/my%20shop/A.java\"")
                .contains("\"uri\": \"file:///C:/work/B.java\"")
                .contains("\"uri\": \"src/my%20shop/C.java\"");
    }

    private static Violation at(String file) {
        return new Violation("r", Violation.Severity.ERROR, file, 1, "m", "f");
    }
}
