package io.github.yoavgeva.designguard.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisabledTestsCheckTest {

    @TempDir
    private Path root;

    @BeforeEach
    void writeTests() {
        new SourceTree(root)
                .add("CartTest", """
                        package shop;

                        class CartTest {
                            @Test
                            @Disabled
                            void total() {
                                assertThat(1).isPositive();
                            }

                            @Test
                            @Disabled("JIRA-12: flaky on CI until the clock is injected")
                            void expiry() {
                                assertThat(1).isPositive();
                            }

                            @org.testng.annotations.Test(enabled = false)
                            void legacy() {
                                assertThat(1).isPositive();
                            }
                        }
                        """)
                .add("OrderTest", """
                        package shop;

                        @Ignore
                        class OrderTest {
                            @Test
                            void place() {
                                assertThat(1).isPositive();
                            }
                        }
                        """);
    }

    @Test
    void reportsEveryDisabledTest() {
        assertThat(violations(false)).extracting(Violation::message).containsExactly(
                "test CartTest.total() is disabled",
                "test CartTest.expiry() is disabled",
                "test CartTest.legacy() is disabled",
                "test class OrderTest is disabled");
    }

    @Test
    void canAllowDisabledTestsThatSayWhy() {
        assertThat(violations(true)).extracting(Violation::message, Violation::fix).containsExactly(
                tuple("test CartTest.total() is disabled",
                        "fix and re-enable it, or say why it's disabled: @Disabled(\"reason\")"),
                tuple("test CartTest.legacy() is disabled",
                        "fix and re-enable it, or say why it's disabled: @Disabled(\"reason\")"),
                tuple("test class OrderTest is disabled",
                        "fix and re-enable it, or say why it's disabled: @Disabled(\"reason\")"));
    }

    private List<Violation> violations(boolean allowWithReason) {
        return new DisabledTestsCheck(allowWithReason).check(SourceReader.read(List.of(root)), ClassFilter.ALL);
    }
}
