package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.InterfacePolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs once for each Java version's class files: see {@link CompiledProject#releases()}. */
@ParameterizedClass(name = "class files for Java {0}")
@MethodSource("io.github.yoavgeva.designguard.CompiledProject#releases")
class InterfacePolicyRuleTest {

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    private DesignGuard guard;

    @BeforeEach
    void compileShop() {
        guard = ShopFixture.write(directory).release(release).guard("shop");
    }

    @Test
    void portsPolicyFlagsConcreteServicesUsedAcrossSlices() {
        assertThat(guard.dependencies().interfacePolicy(InterfacePolicy.PORTS).evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("interface-policy");
            assertThat(violation.file()).endsWith("shop/orders/OrderService.java");
            assertThat(violation.line()).isEqualTo(8);
            assertThat(violation.message()).isEqualTo("OrderService depends on concrete service-like StripeClient"
                    + " (field client, constructor) across slices orders -> payments");
            assertThat(violation.fix())
                    .isEqualTo("depend on an interface instead of StripeClient, and let the wiring code pass it in");
        });
    }

    @Test
    void servicesPolicyAlsoFlagsConcreteServicesInsideASlice() {
        assertThat(guard.dependencies().interfacePolicy(InterfacePolicy.SERVICES).evaluate()).extracting(Violation::message)
                .containsExactly(
                        "OrderAudit depends on concrete service-like OrderService (field orders, constructor)",
                        "OrderService depends on concrete service-like StripeClient (field client, constructor)",
                        "StripeGateway depends on concrete service-like StripeClient (field client, constructor)");
    }

    @Test
    void allPolicyAlsoFlagsConcreteValues() {
        assertThat(guard.dependencies().interfacePolicy(InterfacePolicy.ALL).evaluate()).extracting(Violation::message)
                .contains(
                        "OrderService depends on concrete Money (method pay)",
                        "PriceCalculator depends on concrete Money (method total)",
                        "GoodOrderService depends on concrete Money (method pay)")
                .noneMatch(message -> message.startsWith("App "));
    }

    @Test
    void valuesHoldingPathsAreNotServices(@TempDir Path other) {
        var paths = new CompiledProject(other).release(release)
                .add("Settings", "package shop.config;\n\npublic record Settings(java.nio.file.Path root) {\n}\n")
                .add("Loader", "package shop.loading;\n\npublic class Loader {\n"
                        + "    private final shop.config.Settings settings;\n\n"
                        + "    public Loader(shop.config.Settings settings) {\n        this.settings = settings;\n    }\n\n"
                        + "    public String root() {\n        return settings.root().toString();\n    }\n}\n")
                .guard("shop");

        assertThat(paths.dependencies().interfacePolicy(InterfacePolicy.PORTS).evaluate()).isEmpty();
    }

    @Test
    void slicedByUsesArchUnitCaptureGroups() {
        assertThat(guard.dependencies().interfacePolicy(InterfacePolicy.PORTS).slicedBy("(*)..").evaluate()).isEmpty();
    }

    @Test
    void thatNarrowsTheDependentClasses() {
        assertThat(guard.dependencies().interfacePolicy(InterfacePolicy.SERVICES).that().resideInAPackage("..payments..").evaluate())
                .extracting(Violation::message)
                .containsExactly("StripeGateway depends on concrete service-like StripeClient (field client, constructor)");
    }
}
