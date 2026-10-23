package io.github.yoavgeva.designguard.controller;

import static io.github.yoavgeva.designguard.model.AnnotationName.annotatedWith;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.DesignGuardException;
import java.lang.annotation.Documented;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesignGuardTest {

    @TempDir
    private Path root;

    private DesignGuard guard;

    @BeforeEach
    void writeSources() {
        new SourceTree(root)
                .add("Cart", """
                        package shop.cart;

                        class Cart {
                            int total() {
                                throw new UnsupportedOperationException();
                            }
                        }
                        """)
                .add("Legacy", """
                        package shop.legacy;

                        @Documented
                        class Legacy {
                            // TODO delete
                        }
                        """);
        guard = DesignGuard.forPackages("shop").withSourceRoots(root).build();
    }

    @Test
    void checkFailsWithOneActionableLinePerViolation() {
        var cart = root.resolve("shop/cart/Cart.java");
        var legacy = root.resolve("shop/legacy/Legacy.java");

        assertThatThrownBy(() -> guard.guardrails().noStubs().check())
                .isInstanceOf(AssertionError.class)
                .hasMessage("2 design violations:\n"
                        + cart + ":5: error [no-stubs] Cart.total() is a stub: it throws UnsupportedOperationException"
                        + " -> fix: implement it, or delete the method until it is needed\n"
                        + legacy + ":5: error [no-stubs] TODO left in the code: TODO delete"
                        + " -> fix: finish the work now, or track it in an issue instead of a comment");
    }

    @Test
    void bytecodeRulesFailWhenNoClassesAreFound() {
        assertThatThrownBy(() -> guard.dependencies().injectServices().check())
                .isInstanceOf(DesignGuardException.class)
                .hasMessageStartingWith("Found no compiled classes of packages [shop] on the classpath.");
    }

    @Test
    void thatSelectsPackagesAndExcludingSkipsThem() {
        assertThatThrownBy(() -> guard.guardrails().noStubs().that().resideInAPackage("..legacy..").check())
                .hasMessageStartingWith("1 design violation:\n");
        assertThatCode(() -> guard.guardrails().noStubs().that().resideInAPackage("shop..").excluding("..cart..", "..legacy..").check())
                .doesNotThrowAnyException();
    }

    @Test
    void ignoringSkipsAnnotatedClasses() {
        assertThatThrownBy(() -> guard.guardrails().noStubs().ignoring(annotatedWith(Documented.class)).check())
                .hasMessageStartingWith("1 design violation:\n")
                .hasMessageContaining("Cart.total()");
    }
}
