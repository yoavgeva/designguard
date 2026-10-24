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
    void allowsEmptyHooks() {
        assertThat(violations("Service", """
                package shop;

                abstract class Service implements Runnable {
                    protected void startUp() {
                    }

                    public void run() {
                    }

                    /** Does nothing: a service holds nothing to close. */
                    final void close() {
                    }

                    private void render() {
                    }
                }
                """)).singleElement().extracting(Violation::message).isEqualTo("Service.render() has an empty body");
    }

    @Test
    void methodsThatAProjectSubclassOverridesAreHooks() {
        new SourceTree(root)
                .add("Handler", """
                        package shop;

                        public class Handler {
                            void begin(String sql) {
                            }

                            void end(String sql) {
                            }

                            void flush() {
                            }

                            String describe() {
                                throw new UnsupportedOperationException();
                            }

                            void close() {
                            }

                            Handler copy() {
                                return new Handler() {
                                    @Override
                                    void close() {
                                        System.out.println("closed");
                                    }
                                };
                            }
                        }
                        """)
                .add("SqlHandler", """
                        package shop;

                        public class SqlHandler extends Handler {
                            @Override
                            void begin(String sql) {
                                System.out.println(sql);
                            }

                            void end(String sql, int line) {
                                System.out.println(sql + line);
                            }
                        }
                        """)
                .add("ServerHandler", """
                        package shop;

                        public final class ServerHandler extends SqlHandler {
                            @Override
                            void flush() {
                                System.out.println("flushed");
                            }

                            @Override
                            String describe() {
                                return "server";
                            }
                        }
                        """);

        assertThat(new StubCheck().check(SourceReader.read(List.of(root)), ClassFilter.ALL))
                .singleElement().extracting(Violation::message).isEqualTo("Handler.end() has an empty body");
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
