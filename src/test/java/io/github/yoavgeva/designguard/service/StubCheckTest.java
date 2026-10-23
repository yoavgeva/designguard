package io.github.yoavgeva.designguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StubCheckTest {

    private static final String CART = "Cart";

    @TempDir
    private Path root;

    @Test
    void finishedCodeHasNoViolations() {
        assertThat(violations(CART, """
                package shop;

                class Cart {
                    private Cart() {
                    }

                    int total() {
                        return 3;
                    }

                    void reset() {
                        // nothing to reset: the cart is immutable
                    }
                }
                """)).isEmpty();
    }

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
    void reportsEmptyMethodBodies() {
        assertThat(violations(CART, """
                package shop;

                class Cart {
                    void checkout() {
                    }
                }
                """)).singleElement()
                .extracting(Violation::message).isEqualTo("Cart.checkout() has an empty body");
    }

    @Test
    void allowsEmptyOverridesAndDefaultHooks() {
        assertThat(violations("Listener", """
                package shop;

                interface Listener {
                    default void onEvent() {
                    }
                }
                """)).isEmpty();
    }

    @Test
    void appliesTheFilter() {
        new SourceTree(root).add(CART, """
                package shop.legacy;

                class Cart {
                    // TODO remove
                }
                """);

        assertThat(new StubCheck().check(SourceReader.read(List.of(root)), ClassFilter.ALL.excluding("..legacy..")))
                .isEmpty();
    }

    private List<Violation> violations(String typeName, String source) {
        new SourceTree(root).add(typeName, source);
        return new StubCheck().check(SourceReader.read(List.of(root)), ClassFilter.ALL);
    }
}
