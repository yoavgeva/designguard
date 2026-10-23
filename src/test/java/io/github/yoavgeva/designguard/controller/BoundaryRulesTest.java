package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
class BoundaryRulesTest {

    private static final String SHOP = "shop";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void stableSlicesMustNotDependOnLessStableOnes() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Money", "package shop.core;\n\npublic record Money(long cents) {\n}\n")
                .add("Audit", "package shop.core;\n\npublic class Audit {\n"
                        + "    public String log(shop.reports.Report report) {\n        return report.toString();\n    }\n}\n")
                .add("Invoice", "package shop.billing;\n\npublic class Invoice {\n"
                        + "    public long total(shop.core.Money money) {\n        return money.cents();\n    }\n}\n")
                .add("Report", "package shop.reports;\n\npublic class Report {\n"
                        + "    public String render(shop.billing.Invoice invoice, shop.core.Money money) {\n"
                        + "        return invoice.total(money) + \"\";\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.dependencies().stableDependencies().evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("stable-dependencies");
            assertThat(violation.file()).endsWith("shop/core/Audit.java");
            assertThat(violation.message()).isEqualTo("slice core (instability 0.33) depends on slice reports,"
                    + " which is less stable (instability 0.50)");
            assertThat(violation.fix()).isEqualTo("invert it: let core own an interface that reports implements,"
                    + " or move what core needs into core");
        });
    }

    @Test
    void infrastructureExceptionsStayOutOfTheDomain() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("OrderStore", "package shop.domain;\n\npublic interface OrderStore {\n"
                        + "    String load(String id) throws java.sql.SQLException;\n}\n")
                .add("Order", "package shop.domain;\n\npublic class Order {\n"
                        + "    public Order(String id) {\n        if (id.isBlank()) {\n"
                        + "            throw new IllegalArgumentException(\"blank id\");\n        }\n    }\n}\n")
                .add("JdbcOrders", "package shop.adapter;\n\npublic class JdbcOrders implements shop.domain.OrderStore {\n"
                        + "    public String load(String id) throws java.sql.SQLException {\n"
                        + "        throw new java.sql.SQLException(id);\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.architecture().infrastructureExceptionsStayOutOf("..domain..").evaluate())
                .singleElement().satisfies(violation -> {
                    assertThat(violation.rule()).isEqualTo("domain-exceptions");
                    assertThat(violation.message()).isEqualTo("OrderStore uses the infrastructure exception SQLException");
                    assertThat(violation.fix())
                            .isEqualTo("catch it in an adapter and throw a domain exception instead");
                });
    }

    @Test
    void frameworksStayOutOfTheDomain() {
        String runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n";
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Service", "package org.springframework.stereotype;\n\n" + runtime + "public @interface Service {\n}\n")
                .add("JsonProperty", "package com.fasterxml.jackson.annotation;\n\n" + runtime
                        + "public @interface JsonProperty {\n    String value();\n}\n")
                .add("Order", """
                        package shop.domain;

                        public class Order {
                            @com.fasterxml.jackson.annotation.JsonProperty("id")
                            private final String id;

                            public Order(String id) {
                                this.id = id;
                            }

                            public String id() {
                                return id;
                            }
                        }
                        """)
                .add("Pricing", "package shop.domain;\n\n@org.springframework.stereotype.Service\npublic class Pricing {\n}\n")
                .add("Money", "package shop.domain;\n\npublic record Money(long cents) {\n}\n")
                .add("OrderController", "package shop.web;\n\n@org.springframework.stereotype.Service\n"
                        + "public class OrderController {\n}\n")
                .guard(SHOP);

        assertThat(guard.architecture().frameworksStayOutOf("..domain..").evaluate())
                .extracting(Violation::rule, Violation::message, Violation::fix)
                .containsExactly(
                        tuple("frameworks-stay-out", "Order uses the framework type JsonProperty",
                                "keep the domain plain Java: move the framework code to an adapter and map at the boundary"),
                        tuple("frameworks-stay-out", "Pricing uses the framework type Service",
                                "keep the domain plain Java: move the framework code to an adapter and map at the boundary"));
    }

    @Test
    void entitiesStayBehindTheApi() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Entity", "package shop.persistence;\n\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface Entity {\n}\n")
                .add("OrderEntity", "package shop.persistence;\n\n@Entity\npublic class OrderEntity {\n}\n")
                .add("OrderService", "package shop.service;\n\npublic class OrderService {\n"
                        + "    public shop.persistence.OrderEntity load() {\n        return new shop.persistence.OrderEntity();\n    }\n}\n")
                .add("OrderController", "package shop.web;\n\npublic class OrderController {\n"
                        + "    public shop.persistence.OrderEntity get(shop.service.OrderService service) {\n"
                        + "        return service.load();\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.architecture().entitiesStayOutOf("..web..").evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("entities-stay-behind");
            assertThat(violation.message()).isEqualTo("OrderController uses the entity OrderEntity");
            assertThat(violation.fix())
                    .isEqualTo("map it to a DTO or a domain type before it leaves the persistence layer");
        });
    }
}
