package io.github.yoavgeva.designguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeaningfulTestsCheckTest {

    @TempDir
    private Path root;

    @Test
    void acceptsTestsThatAssertOnTheCodeUnderTest() {
        assertThat(violations("""
                package shop;

                class CartTest {
                    @Test
                    void total() {
                        assertThat(new Cart().total()).isEqualTo(3);
                    }

                    @Test
                    void rejectsNegative() {
                        assertThrows(IllegalArgumentException.class, () -> new Cart().add(-1));
                    }

                    @Test
                    void notifies() {
                        new Cart().checkout(listener);
                        verify(listener).done();
                    }

                    @Test
                    void respondsOk() throws Exception {
                        mvc.perform(get("/cart")).andExpect(status().isOk());
                    }
                }
                """)).isEmpty();
    }

    @Test
    void reportsATestWithoutAssertions() {
        assertThat(violations("""
                package shop;

                class CartTest {
                    @Test
                    void total() {
                        new Cart().total();
                    }
                }
                """)).singleElement().satisfies(violation -> {
                    assertThat(violation.rule()).isEqualTo("meaningful-tests");
                    assertThat(violation.line()).isEqualTo(5);
                    assertThat(violation.message()).isEqualTo("test CartTest.total() asserts nothing");
                    assertThat(violation.fix()).isEqualTo("assert on the result of the code under test");
                });
    }

    @Test
    void reportsAssertionsThatCanNeverFail() {
        assertThat(violations("""
                package shop;

                class CartTest {
                    @ParameterizedTest
                    void total(int size) {
                        assertTrue(true);
                        assertThat(1).isEqualTo(1);
                    }

                    @Test
                    void same() {
                        int total = new Cart().total();
                        assertEquals(total, total);
                        assertThat(total).isEqualTo(total);
                    }
                }
                """)).extracting(Violation::message).containsExactly(
                        "test CartTest.total() asserts on constants, which can never fail",
                        "test CartTest.total() asserts on constants, which can never fail",
                        "test CartTest.same() compares total with itself, which can never fail",
                        "test CartTest.same() compares total with itself, which can never fail");
    }

    @Test
    void acceptsRuleChecksAndAwaitilityAsAssertions() {
        assertThat(violations("""
                package shop;

                class DesignTest {
                    @Test
                    void layers() {
                        LAYERS.check(CLASSES);
                    }

                    @Test
                    void noStubs() {
                        GUARD.guardrails().noStubs().check();
                    }

                    @Test
                    void eventually() {
                        await().untilAsserted(() -> cart.isEmpty());
                    }
                }
                """)).isEmpty();
    }

    @Test
    void acceptsJUnit4ExpectedExceptions() {
        assertThat(violations("""
                package shop;

                class CartTest {
                    @Test(expected = IllegalArgumentException.class)
                    void rejectsNegative() {
                        new Cart().add(-1);
                    }
                }
                """)).isEmpty();
    }

    private List<Violation> violations(String source) {
        new SourceTree(root).add("CartTest", source);
        return new MeaningfulTestsCheck().check(SourceReader.read(List.of(root)), ClassFilter.ALL);
    }
}
