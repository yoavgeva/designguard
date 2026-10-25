package io.github.yoavgeva.designguard.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Throws that stand in for code: unfinished, or an unexplained refusal; deliberate refusals are fine. */
class PlaceholderThrowsTest {

    private static final String CART = "Cart";

    @TempDir
    private Path root;

    @Test
    void reportsUnsupportedOperationPlaceholders() {
        assertThat(violations(CART, """
                package shop;

                class Cart {
                    int total() {
                        throw new UnsupportedOperationException();
                    }
                }
                """)).singleElement().satisfies(violation -> {
                    assertThat(violation.rule()).isEqualTo("no-stubs");
                    assertThat(violation.file()).endsWith("shop/Cart.java");
                    assertThat(violation.line()).isEqualTo(5);
                    assertThat(violation.message()).isEqualTo("Cart.total() is a stub: it throws UnsupportedOperationException");
                });
    }

    @Test
    void allowsUnsupportedOperationInOverridesThatRefuseAnOperation() {
        assertThat(violations("ReadOnlyIterator", """
                package shop;

                import java.util.Iterator;

                abstract class ReadOnlyIterator implements Iterator<String> {
                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException("read-only");
                    }
                }
                """)).isEmpty();
    }

    @Test
    void reportsNotImplementedMessagesEvenInOverrides() {
        assertThat(violations("Task", """
                package shop;

                class Task implements Runnable {
                    @Override
                    public void run() {
                        throw new UnsupportedOperationException("Not implemented yet");
                    }
                }
                """)).hasSize(1);
    }

    @Test
    void reportsThrowsWhoseMessageSaysTheCodeIsUnfinished() {
        assertThat(violations(CART, """
                package shop;

                class Cart {
                    int total() {
                        throw new RuntimeException("Not implemented yet");
                    }

                    int tax(int cents) {
                        if (cents > 0) {
                            return cents / 10;
                        }
                        throw new IllegalStateException("TODO");
                    }

                    int fees() {
                        throw new NotImplementedException();
                    }

                    int discount(int cents) {
                        if (cents > 0) {
                            throw new UnsupportedOperationException("not implemented");
                        }
                        return 0;
                    }
                }
                """)).extracting(Violation::line, Violation::message).containsExactly(
                        tuple(5, "Cart.total() is a stub: it throws RuntimeException"),
                        tuple(12, "Cart.tax() is a stub: it throws IllegalStateException"),
                        tuple(16, "Cart.fees() is a stub: it throws NotImplementedException"),
                        tuple(21, "Cart.discount() is a stub: it throws UnsupportedOperationException"));
    }

    @Test
    void allowsDeliberateRefusals() {
        assertThat(violations("Shelf", """
                package shop;

                import java.util.Iterator;

                abstract class Shelf implements Iterable<String> {
                    public void restock() {
                        throw new UnsupportedOperationException();
                    }

                    @Deprecated
                    final void clear() {
                        throw new UnsupportedOperationException();
                    }

                    /** Always throws: shelves are fixed to the wall. */
                    final void move() {
                        throw new UnsupportedOperationException();
                    }

                    final void lock() {
                        throw new UnsupportedOperationException("shelves lock themselves");
                    }

                    final int weight() {
                        throw new UnsupportedOperationException("weight() not implemented for shelves");
                    }

                    final int count(String item) {
                        if (item.isEmpty()) {
                            throw new UnsupportedOperationException();
                        }
                        return 1;
                    }

                    static final class Labels implements Iterator<String> {
                        private Labels() {
                            throw new UnsupportedOperationException();
                        }

                        public boolean hasNext() {
                            return false;
                        }

                        public String next() {
                            throw new UnsupportedOperationException();
                        }
                    }
                }
                """)).isEmpty();
    }

    private List<Violation> violations(String typeName, String source) {
        new SourceTree(root).add(typeName, source);
        JavaSources sources = SourceReader.read(List.of(root));
        PlaceholderThrows placeholders = new PlaceholderThrows("no-stubs", OverriddenMethods.in(sources));
        return sources.files().stream()
                .flatMap(file -> placeholders.find(file, ClassFilter.ALL))
                .toList();
    }
}
