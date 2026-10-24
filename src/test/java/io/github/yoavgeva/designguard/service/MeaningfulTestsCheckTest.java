package io.github.yoavgeva.designguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

    @Test
    void followsHelpersThatAssert() {
        new SourceTree(root)
                .add("BaseTest", """
                        package shop;

                        abstract class BaseTest {
                            void checkTax(Cart cart) {
                                if (cart.tax() < 0) {
                                    throw new AssertionError("negative tax");
                                }
                            }
                        }
                        """)
                .add("Carts", """
                        package shop;

                        final class Carts {
                            static void feesAreZero(Cart cart) {
                                assertEquals(0, cart.fees());
                            }
                        }
                        """);

        assertThat(violations("""
                package shop;

                class CartTest extends BaseTest {
                    @Test
                    void total() {
                        totalIs(new Cart(), 3);
                    }

                    @Test
                    void tax() {
                        checkTax(new Cart());
                    }

                    @Test
                    void fees() {
                        Carts.feesAreZero(new Cart());
                    }

                    @Test
                    void shouldNotThrowForAnEmptyCart() {
                        new Cart().checkout();
                    }

                    @Test
                    void parsesAnEmptyCart() {
                        // should not throw an exception
                        new Cart().parse("");
                    }

                    @Test
                    void parsesSpaces() {
                        // Expect nothing thrown
                        new Cart().parse(" ");
                    }

                    @Test
                    void parsesTabs() {
                        // should not blow up
                        new Cart().parse("tab");
                    }

                    @Test
                    void parsesNewLines() {
                        // the parser doesn't actually throw here
                        new Cart().parse("line");
                    }

                    @Test
                    void parsesWithoutThrowing() {
                        new Cart().parse("x");
                    }

                    @Test
                    void parsesTwice() {
                        // now throws if parsed twice, which this doesn't check
                        new Cart().parse("y");
                    }

                    @Test
                    void checksOut() {
                        helps(new Cart());
                    }

                    private void totalIs(Cart cart, int total) {
                        assertEquals(total, cart.total());
                    }

                    private void helps(Cart cart) {
                        cart.checkout();
                    }
                }
                """)).extracting(Violation::message).containsExactly(
                        "test CartTest.parsesTwice() asserts nothing", "test CartTest.checksOut() asserts nothing");
    }

    @Test
    void constantsGivenToOwnHelpersAreInputs() {
        assertThat(violations("""
                package shop;

                class CartTest {
                    @Test
                    void escapes() {
                        assertEscaped("\\t", "\t");
                        assertThat(1).isEqualTo(1);
                        assertThat("2.8.5").matches(Escapes.VERSION);
                        assertWithMessage("setup is broken").that(Escapes.name()).isEqualTo("x");
                    }

                    private void assertEscaped(String expected, String input) {
                        assertEquals(expected, Escapes.escape(input));
                    }
                }
                """)).extracting(Violation::line, Violation::message).containsExactly(
                        tuple(7, "test CartTest.escapes() asserts on constants, which can never fail"));
    }

    private List<Violation> violations(String source) {
        new SourceTree(root).add("CartTest", source);
        return new MeaningfulTestsCheck().check(SourceReader.read(List.of(root)), ClassFilter.ALL);
    }
}
