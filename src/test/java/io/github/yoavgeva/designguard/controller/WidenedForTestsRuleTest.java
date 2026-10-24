package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs once for each Java version's class files: see {@link CompiledProject#releases()}. */
@ParameterizedClass(name = "class files for Java {0}")
@MethodSource("io.github.yoavgeva.designguard.CompiledProject#releases")
class WidenedForTestsRuleTest {

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void codeOnlyTestsCallStaysPrivate() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Orders", """
                        package shop;

                        import java.util.ArrayList;
                        import java.util.List;

                        public class Orders {
                            private final List<String> items = new ArrayList<>();

                            public Orders() {
                                items.add("first");
                            }

                            Orders(List<String> seed) {
                                items.addAll(seed);
                            }

                            protected Orders(String first) {
                                items.add(first);
                            }

                            public int count() {
                                return items.size() + recount() + cacheSize();
                            }

                            @VisibleForTesting
                            int cacheSize() {
                                return items.size();
                            }

                            int recount() {
                                return 0;
                            }

                            int recalculate() {
                                return items.size() * 2;
                            }

                            protected void reset() {
                                items.clear();
                            }

                            void unused() {
                                items.clear();
                            }
                        }
                        """)
                .add("VisibleForTesting", "package shop;\n\npublic @interface VisibleForTesting {\n}\n")
                .addTest("OrdersTest", """
                        package shop;

                        import static org.assertj.core.api.Assertions.assertThat;

                        class OrdersTest {
                            @org.junit.jupiter.api.Test
                            void counts() {
                                Orders orders = new Orders(java.util.List.of("a"));
                                assertThat(new Orders("b").cacheSize()).isOne();
                                assertThat(orders.recalculate()).isEqualTo(2);
                                orders.reset();
                                assertThat(orders.count()).isZero();
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.guardrails().noWidenedForTests().evaluate()).extracting(Violation::message)
                .containsExactlyInAnyOrder(
                        "Orders(List) is package-private, and only tests call it",
                        "Orders.recalculate() is package-private, and only tests call it",
                        "Orders.reset() is protected, and only tests call it",
                        "Orders.cacheSize() is package-private for tests: it's marked @VisibleForTesting");
        assertThat(guard.guardrails().noWidenedForTests().evaluate()).extracting(Violation::fix).containsOnly(
                "test through the public API, or move it into a class of its own whose API tests and production both use");
    }
}
