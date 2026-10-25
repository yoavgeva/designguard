package io.github.yoavgeva.designguard.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.TypeDispatchPolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeDispatchPlacesTest {

    private static final int DEFAULT_MAX = 3;

    @TempDir
    private Path root;

    private SourceTree tree;

    @BeforeEach
    void writeTypes() {
        tree = DispatchFixture.writeTypes(root);
    }

    @Test
    void reportsATypeBranchedOnInTooManyPlaces() {
        addEnumChecks("Checkout", "Invoice", "Label", "Refund");

        List<Violation> violations = violations(TypeDispatchPolicy.SEALED_ONLY, DEFAULT_MAX);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.file()).endsWith("shop/OrderType.java");
            assertThat(violation.line()).isEqualTo(3);
            assertThat(violation.message()).isEqualTo("OrderType is branched on in 4 places the compiler can't check"
                    + " (max 3): Checkout.java:5, Invoice.java:5, Label.java:5, Refund.java:5");
            assertThat(violation.fix()).isEqualTo("move the varying behaviour into OrderType,"
                    + " or switch over it exhaustively without a default branch");
        });
        assertThat(violations(TypeDispatchPolicy.SEALED_ONLY, 4)).isEmpty();
    }

    @Test
    void exhaustiveSwitchesDoNotCountTowardsTheLimit() {
        for (String name : List.of("Checkout", "Invoice", "Label", "Refund")) {
            tree.add(name, """
                    package shop;

                    class %s {
                        int fee(OrderType type) {
                            return switch (type) {
                                case EXPRESS -> 10;
                                case PICKUP, STANDARD -> 0;
                            };
                        }
                    }
                    """.formatted(name));
        }

        assertThat(violations(TypeDispatchPolicy.SEALED_ONLY, DEFAULT_MAX)).isEmpty();
    }

    @Test
    void branchingInsideTheTypeItselfDoesNotCount() {
        tree.add("Priority", """
                package shop;

                public enum Priority {
                    HIGH, LOW;

                    int weight() {
                        if (this == HIGH) {
                            return 3;
                        }
                        if (this == LOW) {
                            return 1;
                        }
                        if (this != HIGH && this != LOW) {
                            return 0;
                        }
                        return this == HIGH ? 2 : 1;
                    }
                }
                """);

        assertThat(violations(TypeDispatchPolicy.SEALED_ONLY, DEFAULT_MAX)).isEmpty();
    }

    private void addEnumChecks(String... classNames) {
        for (String name : classNames) {
            tree.add(name, """
                    package shop;

                    class %s {
                        int fee(OrderType type) {
                            if (type == OrderType.EXPRESS) {
                                return 10;
                            }
                            return 0;
                        }
                    }
                    """.formatted(name));
        }
    }

    private List<Violation> violations(TypeDispatchPolicy policy, int maxPlacesPerType) {
        return DispatchFixture.violations(root, policy, maxPlacesPerType);
    }
}
