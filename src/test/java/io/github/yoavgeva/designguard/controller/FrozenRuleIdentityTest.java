package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.DesignGuardException;
import io.github.yoavgeva.designguard.model.InterfacePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A frozen violation is matched by what it is about, not by details that move as the code changes. */
class FrozenRuleIdentityTest {

    private static final String SHOP = "shop";
    private static final String FROZEN = "frozen";

    @TempDir
    private Path directory;

    @Test
    void stableDependencyViolationsStayFrozenWhenInstabilityShifts() {
        CompiledProject project = new CompiledProject(directory)
                .add("Money", "package shop.core;\n\npublic record Money(long cents) {\n}\n")
                .add("Audit", "package shop.core;\n\npublic class Audit {\n"
                        + "    public String log(shop.reports.Report report) {\n        return report.toString();\n    }\n}\n")
                .add("Invoice", "package shop.billing;\n\npublic class Invoice {\n"
                        + "    public long total(shop.core.Money money) {\n        return money.cents();\n    }\n}\n")
                .add("Report", "package shop.reports;\n\npublic class Report {\n"
                        + "    public String render(shop.billing.Invoice invoice, shop.core.Money money) {\n"
                        + "        return invoice.total(money) + \"\";\n    }\n}\n");
        assertThat(guard(project).dependencies().stableDependencies().frozen("stable").evaluate()).hasSize(1);

        project.add("Chart", "package shop.reports;\n\npublic class Chart {\n"
                + "    public long plot(shop.billing.Invoice invoice) {\n        return invoice.total(null);\n    }\n}\n");

        assertThat(guard(project).dependencies().stableDependencies().evaluate()).singleElement()
                .satisfies(violation -> assertThat(violation.message()).doesNotContain("instability 0.50"));
        assertThat(guard(project).dependencies().stableDependencies().frozen("stable").evaluate()).isEmpty();
    }

    @Test
    void interfacePolicyViolationsStayFrozenWhenUsagesChange() {
        String client = "package shop;\n\npublic class StripeClient {\n"
                + "    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();\n\n"
                + "    public String charge() {\n        return http.toString();\n    }\n}\n";
        CompiledProject project = new CompiledProject(directory).add("StripeClient", client)
                .add("Checkout", "package shop;\n\npublic class Checkout {\n    private StripeClient client;\n\n"
                        + "    public String pay() {\n        return String.valueOf(client);\n    }\n}\n");
        assertThat(guard(project).dependencies().interfacePolicy(InterfacePolicy.SERVICES).frozen("ports").evaluate())
                .hasSize(1);

        project.add("Checkout", "package shop;\n\npublic class Checkout {\n    private StripeClient client;\n\n"
                + "    public String pay(StripeClient other) {\n        return String.valueOf(client) + other;\n    }\n}\n");

        assertThat(guard(project).dependencies().interfacePolicy(InterfacePolicy.SERVICES).frozen("ports").evaluate())
                .isEmpty();
    }

    @Test
    void twoRulesFrozenUnderOneNameFailInsteadOfErasingEachOther() throws IOException {
        CompiledProject project = new CompiledProject(directory)
                .add("OrderController", "package shop.web;\n\npublic class OrderController {\n}\n")
                .add("OrderRepository", "package shop.persistence;\n\npublic class OrderRepository {\n"
                        + "    // TODO page the results\n"
                        + "    public String notify(shop.web.OrderController controller) {\n"
                        + "        return controller.toString();\n    }\n}\n");
        DesignGuard guard = guard(project);
        guard.architecture().layered().layer("Web", "..web..").layer("Persistence", "..persistence..")
                .frozen("shared").evaluate();
        Path record = directory.resolve(FROZEN).resolve("shared.txt");
        String recorded = Files.readString(record);

        assertThatThrownBy(() -> guard.guardrails().noStubs().frozen("shared").evaluate())
                .isInstanceOf(DesignGuardException.class)
                .hasMessage(record + " records violations of layered-architecture, not of no-stubs: give each frozen"
                        + " rule its own name");
        assertThat(Files.readString(record)).isEqualTo(recorded);
    }

    private DesignGuard guard(CompiledProject project) {
        return project.configure(SHOP).freezingIn(directory.resolve(FROZEN)).build();
    }
}
