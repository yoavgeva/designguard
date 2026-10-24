package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BranchingTestsRuleTest {

    @TempDir
    private Path directory;

    @Test
    void testsTakeOnePath() {
        DesignGuard guard = new CompiledProject(directory)
                .add("Cart", "package shop;\n\npublic class Cart {\n    public int total() {\n        return 3;\n    }\n}\n")
                .addTest("CartTest", """
                        package shop;

                        import static org.assertj.core.api.Assertions.assertThat;

                        class CartTest {
                            @org.junit.jupiter.api.Test
                            void totals() {
                                Cart cart = new Cart();
                                if (cart.total() > 0) {
                                    assertThat(cart.total()).isEqualTo(3);
                                }
                            }

                            @org.junit.jupiter.api.Test
                            void totalsEachTime() {
                                for (int round = 0; round < 3; round++) {
                                    assertThat(new Cart().total()).isEqualTo(3);
                                }
                            }

                            @org.junit.jupiter.params.ParameterizedTest
                            @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
                            void totalsForEveryCount(int count) {
                                assertThat(new Cart().total() * count).isPositive();
                            }

                            @org.junit.jupiter.api.Test
                            void totalsAfterFillingIt() {
                                java.util.List<Cart> carts = new java.util.ArrayList<>();
                                for (int round = 0; round < 3; round++) {
                                    carts.add(new Cart());
                                }
                                assertThat(carts.get(2).total()).isEqualTo(3);
                            }

                            @org.junit.jupiter.api.Test
                            void totalsOnce() {
                                assertThat(new Cart().total()).isEqualTo(expected(1));
                            }

                            @org.junit.jupiter.api.Test
                            void totalsWithAFakeSupplier() {
                                java.util.function.IntSupplier fake = new java.util.function.IntSupplier() {
                                    @Override
                                    public int getAsInt() {
                                        if (System.nanoTime() > 0) {
                                            return 3;
                                        }
                                        return 0;
                                    }
                                };
                                class Fixed implements java.util.function.IntSupplier {
                                    @Override
                                    public int getAsInt() {
                                        for (int round = 0; round < 3; round++) {
                                            assertThat(round).isNotNegative();
                                        }
                                        return 3;
                                    }
                                }
                                assertThat(fake.getAsInt() + new Fixed().getAsInt()).isEqualTo(6);
                            }

                            @org.junit.jupiter.api.Test
                            void totalsWithAFakeFunction() {
                                java.util.function.IntUnaryOperator fake = count -> {
                                    if (count > 2) {
                                        return 3;
                                    }
                                    return 0;
                                };
                                assertThat(fake.applyAsInt(3)).isEqualTo(3);
                            }

                            @org.junit.jupiter.api.Test
                            void totalsOnlyPositives() {
                                java.util.List.of(1, -1).forEach(count -> {
                                    if (count > 0) {
                                        assertThat(count).isPositive();
                                    }
                                });
                            }

                            private int expected(int carts) {
                                int sum = 0;
                                for (int cart = 0; cart < carts; cart++) {
                                    sum += 3;
                                }
                                return sum;
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.tests().testsHaveNoLogic().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(9, "test CartTest.totals() has an if: a test should take one path"),
                        tuple(16, "test CartTest.totalsEachTime() has a for loop: a test should take one path"),
                        tuple(78, "test CartTest.totalsOnlyPositives() has an if: a test should take one path"));
        assertThat(guard.tests().testsHaveNoLogic().evaluate().get(0).fix())
                .isEqualTo("split it into one test per case, or make the cases parameters of a @ParameterizedTest");
    }
}
