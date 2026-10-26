package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yoavgeva.designguard.model.DesignGuardException;
import io.github.yoavgeva.designguard.model.TypeDispatchPolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rules adapt to the Java version the checked code targets, read from its class files. */
class JavaReleaseTest {

    private static final String SHOP = "shop";
    /** The class file major version Java 99 will write: Java N writes N + 44. */
    private static final int JAVA_99 = 143;
    private static final String CART_TYPE = "Cart";
    private static final String CART = """
            package shop;

            public final class Cart {
                private final int items;

                public Cart(int items) {
                    this.items = items;
                }

                public int items() {
                    return items;
                }
            }
            """;

    @TempDir
    private Path directory;

    @Test
    void recordRulesFailClearlyWhenTheCodeTargetsJavaBefore16() {
        var guard = new CompiledProject(directory).release(11).add(CART_TYPE, CART).guard(SHOP);

        assertThatThrownBy(() -> guard.types().dataClassesAreRecords().evaluate())
                .isInstanceOf(DesignGuardException.class)
                .hasMessage("dataClassesAreRecords() needs records (Java 16+), but the checked code targets Java 11."
                        + " Remove the rule, or compile for Java 16 or later. designguard reads the Java version from"
                        + " the class files; forJava(...) overrides it.");
    }

    @Test
    void sealedRulesFailClearlyWhenTheCodeTargetsJavaBefore17() {
        var guard = new CompiledProject(directory).release(16).add(CART_TYPE, CART).guard(SHOP);

        assertThatThrownBy(() -> guard.types().closedHierarchiesAreSealed().evaluate())
                .isInstanceOf(DesignGuardException.class)
                .hasMessageStartingWith("closedHierarchiesAreSealed() needs sealed types (Java 17+), but the checked"
                        + " code targets Java 16.");
        assertThatThrownBy(() -> guard.types().typeDispatch(TypeDispatchPolicy.SEALED_ONLY).evaluate())
                .isInstanceOf(DesignGuardException.class)
                .hasMessageStartingWith("typeDispatch(SEALED_ONLY) needs sealed types (Java 17+)");
        assertThat(guard.types().typeDispatch(TypeDispatchPolicy.ALLOW).evaluate()).isEmpty();
    }

    @Test
    void forJavaOverridesTheVersionReadFromTheClassFiles() {
        var configured = new CompiledProject(directory).add(CART_TYPE, CART).configure(SHOP);

        assertThat(configured.build().types().dataClassesAreRecords().evaluate()).extracting(Violation::message)
                .containsExactly("Cart only carries data: make it a record");
        assertThatThrownBy(() -> configured.forJava(11).build().types().dataClassesAreRecords().evaluate())
                .isInstanceOf(DesignGuardException.class)
                .hasMessageContaining("targets Java 11");
    }

    @Test
    void classFilesNewerThanDesignguardCanReadFailClearly() {
        var guard = new CompiledProject(directory).stampMajorVersion(JAVA_99).add(CART_TYPE, CART).guard(SHOP);

        assertThatThrownBy(() -> guard.dependencies().injectServices().evaluate())
                .isInstanceOf(DesignGuardException.class)
                .hasMessage("Found no compiled classes of packages [shop] in [" + directory.resolve("classes") + "]."
                        + " Check the package names, and that the code is compiled before designguard runs. If it is,"
                        + " its class files may target a newer Java than designguard can read: upgrade designguard, or"
                        + " compile for an older Java.");
        assertThat(guard.guardrails().noStubs().evaluate()).isEmpty();
    }

    @Test
    void forJavaNeedsJava8OrLater() {
        var builder = DesignGuard.forPackages(SHOP);

        assertThatIllegalArgumentException().isThrownBy(() -> builder.forJava(7))
                .withMessage("forJava needs Java 8 or later, was 7");
    }

    @Test
    void typeDispatchSuggestsSwitchingOnTypesOnlyFromJava21() {
        var configured = new CompiledProject(directory)
                .add("Animal", "package shop;\n\npublic interface Animal {\n}\n")
                .add("Cat", "package shop;\n\npublic record Cat() implements Animal {\n}\n")
                .add("Dog", "package shop;\n\npublic record Dog() implements Animal {\n}\n")
                .add("Shape", "package shop;\n\npublic sealed interface Shape permits Circle, Square {\n}\n")
                .add("Circle", "package shop;\n\npublic record Circle(double r) implements Shape {\n}\n")
                .add("Square", "package shop;\n\npublic record Square(double side) implements Shape {\n}\n")
                .add("Vet", """
                        package shop;

                        public class Vet {
                            public String treat(Animal animal) {
                                if (animal instanceof Cat) {
                                    return "cat";
                                } else if (animal instanceof Dog) {
                                    return "dog";
                                }
                                return "other";
                            }

                            public double size(Shape shape) {
                                if (shape instanceof Circle circle) {
                                    return circle.r();
                                } else if (shape instanceof Square square) {
                                    return square.side();
                                }
                                return 0;
                            }
                        }
                        """)
                .configure(SHOP);

        assertThat(configured.build().types().typeDispatch(TypeDispatchPolicy.SEALED_ONLY).evaluate())
                .extracting(Violation::fix)
                .containsExactly("make Animal sealed, or move the varying behaviour into Animal");
        assertThat(configured.forJava(21).build().types().typeDispatch(TypeDispatchPolicy.SEALED_ONLY).evaluate())
                .extracting(Violation::fix)
                .containsExactly("make Animal sealed and switch over it, or move the varying behaviour into Animal",
                        "use a switch over Shape without a default branch");
    }

    @Test
    void threadHintsUseVirtualThreadsOnlyFromJava21() {
        var configured = new CompiledProject(directory)
                .add("Jobs", """
                        package shop;

                        public class Jobs {
                            public void run(Runnable job) {
                                new Thread(job).start();
                            }
                        }
                        """)
                .configure(SHOP);

        assertThat(configured.build().dependencies().noRawThreads().evaluate()).extracting(Violation::fix)
                .containsExactly("submit the work to an injected ExecutorService, e.g. one from"
                        + " Executors.newFixedThreadPool(n)");
        assertThat(configured.forJava(21).build().dependencies().noRawThreads().evaluate()).extracting(Violation::fix)
                .containsExactly("submit the work to an injected ExecutorService, e.g. one from"
                        + " Executors.newVirtualThreadPerTaskExecutor()");
    }
}
