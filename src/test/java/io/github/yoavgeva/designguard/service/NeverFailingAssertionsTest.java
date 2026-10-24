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

/** Assertions that can never fail, and tests that pass whatever the code does. */
class NeverFailingAssertionsTest {

    @TempDir
    private Path root;

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
    void equalsTestsAndRepeatedCallsCanFail() {
        assertThat(violations("""
                package shop;

                class RangeTest {
                    @Test
                    void testEquals() {
                        Range range = new Range(1, 2);
                        assertEquals(range, range);
                        assertThat(range).isEqualTo(range);
                    }

                    @Test
                    void hashesTheSameEachTime() {
                        Range range = new Range(1, 2);
                        assertEquals(range.hash(), range.hash());
                    }
                }
                """)).isEmpty();
    }

    @Test
    void comparingAnObjectWithItselfChecksEqualsUnlessItComparesIdentity() {
        assertThat(violations("""
                package shop;

                class CartTest {
                    @Test
                    void range() {
                        Range range = new Range(1, 2);
                        assertEquals(range, range);
                        assertSame(range, range);
                        int size = range.size();
                        assertEquals(size, size);
                    }
                }
                """)).extracting(Violation::line, Violation::message).containsExactly(
                        tuple(8, "test CartTest.range() compares range with itself, which can never fail"),
                        tuple(10, "test CartTest.range() compares size with itself, which can never fail"));
    }

    @Test
    void reportsAssertingThatANewObjectIsNotNull() {
        assertThat(violations("""
                package shop;

                class CartTest {
                    @Test
                    void creates() {
                        assertNotNull(new Cart());
                        assertThat(new Cart()).isNotNull();
                        assertNotNull(new Cart().total());
                    }
                }
                """)).extracting(Violation::line, Violation::message).containsExactly(
                        tuple(6, "test CartTest.creates() asserts that a new object isn't null, which can never fail"),
                        tuple(7, "test CartTest.creates() asserts that a new object isn't null, which can never fail"));
    }

    @Test
    void reportsAssertionsOnlyInACatchBlock() {
        assertThat(violations("""
                package shop;

                class CartTest {
                    @Test
                    void rejects() {
                        try {
                            new Cart().add(-1);
                        } catch (IllegalArgumentException e) {
                            assertEquals("negative", e.getMessage());
                        }
                    }

                    @Test
                    void rejectsAndFails() {
                        try {
                            new Cart().add(-1);
                            fail("no exception");
                        } catch (IllegalArgumentException e) {
                            assertEquals("negative", e.getMessage());
                        }
                    }

                    @Test
                    void accepts() {
                        try {
                            new Cart().add(1);
                        } catch (IllegalArgumentException e) {
                            fail(e.getMessage());
                        }
                    }
                }
                """)).singleElement().satisfies(violation -> {
                    assertThat(violation.message())
                            .isEqualTo("test CartTest.rejects() asserts only in a catch block, so it passes when nothing is thrown");
                    assertThat(violation.fix()).isEqualTo("use assertThrows, or call fail() after the code that should throw");
                });
    }

    private List<Violation> violations(String source) {
        new SourceTree(root).add("CartTest", source);
        return new MeaningfulTestsCheck().check(SourceReader.read(List.of(root)), ClassFilter.ALL);
    }
}
