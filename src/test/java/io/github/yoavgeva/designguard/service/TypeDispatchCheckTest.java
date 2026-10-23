package io.github.yoavgeva.designguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.TypeDispatchPolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeDispatchCheckTest {

    private static final int DEFAULT_MAX = 3;

    @TempDir
    private Path root;

    private SourceTree tree;

    @BeforeEach
    void writeTypes() {
        tree = DispatchFixture.writeTypes(root);
    }

    @Test
    void exhaustiveSwitchOverASealedTypeIsFine() {
        tree.add("Areas", """
                package shop;

                class Areas {
                    double area(Shape shape) {
                        return switch (shape) {
                            case Circle c -> c.r() * c.r();
                            case Square s -> s.side() * s.side();
                        };
                    }
                }
                """);

        assertThat(violations(TypeDispatchPolicy.SEALED_ONLY, DEFAULT_MAX)).isEmpty();
    }

    @Test
    void reportsATypeSwitchOverANonSealedType() {
        tree.add("Sounds", """
                package shop;

                class Sounds {
                    String sound(Animal animal) {
                        return switch (animal) {
                            case Cat c -> "meow";
                            case Dog d -> "woof";
                            default -> "";
                        };
                    }
                }
                """);

        assertThat(violations(TypeDispatchPolicy.SEALED_ONLY, DEFAULT_MAX)).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("type-dispatch");
            assertThat(violation.file()).endsWith("shop/Sounds.java");
            assertThat(violation.line()).isEqualTo(5);
            assertThat(violation.message())
                    .isEqualTo("switch on the subtypes of Animal, which isn't sealed, so a new subtype won't be caught");
            assertThat(violation.fix())
                    .isEqualTo("make Animal sealed and switch over it, or move the varying behaviour into Animal");
        });
    }

    @Test
    void reportsAnInstanceofChainOverANonSealedType() {
        tree.add("Sounds", """
                package shop;

                class Sounds {
                    String sound(Animal animal) {
                        if (animal instanceof Cat) {
                            return "meow";
                        } else if (animal instanceof Dog) {
                            return "woof";
                        }
                        return "";
                    }
                }
                """);

        assertThat(violations(TypeDispatchPolicy.SEALED_ONLY, DEFAULT_MAX)).singleElement()
                .extracting(Violation::message)
                .isEqualTo("instanceof chain on the subtypes of Animal, which isn't sealed, so a new subtype won't be caught");
    }

    @Test
    void reportsAnInstanceofChainOverASealedType() {
        tree.add("Areas", """
                package shop;

                class Areas {
                    double area(Shape shape) {
                        if (shape instanceof Circle c) {
                            return c.r();
                        } else if (shape instanceof Square s) {
                            return s.side();
                        }
                        return 0;
                    }
                }
                """);

        assertThat(violations(TypeDispatchPolicy.SEALED_ONLY, DEFAULT_MAX)).singleElement().satisfies(violation -> {
            assertThat(violation.message())
                    .isEqualTo("instanceof chain on sealed Shape: the compiler can't check that it covers every subtype");
            assertThat(violation.fix()).isEqualTo("use a switch over Shape without a default branch");
        });
    }

    @Test
    void reportsADefaultBranchThatHidesNewSubtypes() {
        tree.add("Areas", """
                package shop;

                class Areas {
                    double area(Shape shape) {
                        return switch (shape) {
                            case Circle c -> c.r();
                            default -> 0;
                        };
                    }
                }
                """);

        assertThat(violations(TypeDispatchPolicy.SEALED_ONLY, DEFAULT_MAX)).singleElement().satisfies(violation -> {
            assertThat(violation.message())
                    .isEqualTo("switch over sealed Shape has a default branch, so the compiler won't flag a new subtype");
            assertThat(violation.fix()).isEqualTo("remove the default branch and handle every subtype");
        });
    }

    @Test
    void reportsADefaultBranchThatHidesNewEnumConstants() {
        tree.add("Fees", """
                package shop;

                class Fees {
                    int fee(OrderType type) {
                        return switch (type) {
                            case EXPRESS -> 10;
                            default -> 0;
                        };
                    }
                }
                """);

        assertThat(violations(TypeDispatchPolicy.SEALED_ONLY, DEFAULT_MAX)).singleElement()
                .extracting(Violation::message)
                .isEqualTo("switch over enum OrderType has a default branch, so the compiler won't flag a new constant");
    }

    @Test
    void allowPolicyAcceptsTypeSwitchesOverNonSealedTypes() {
        tree.add("Sounds", """
                package shop;

                class Sounds {
                    String sound(Animal animal) {
                        return switch (animal) {
                            case Cat c -> "meow";
                            case Dog d -> "woof";
                            default -> "";
                        };
                    }
                }
                """);

        assertThat(violations(TypeDispatchPolicy.ALLOW, DEFAULT_MAX)).isEmpty();
    }

    @Test
    void ignoresTypesThatAreNotPartOfTheProject() {
        tree.add("Printer", """
                package shop;

                class Printer {
                    String print(Object value) {
                        if (value instanceof String s) {
                            return s;
                        } else if (value instanceof Integer i) {
                            return Integer.toString(i);
                        }
                        return "";
                    }
                }
                """);

        assertThat(violations(TypeDispatchPolicy.SEALED_ONLY, DEFAULT_MAX)).isEmpty();
    }

    private List<Violation> violations(TypeDispatchPolicy policy, int maxPlacesPerType) {
        return DispatchFixture.violations(root, policy, maxPlacesPerType);
    }
}
