package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.InheritancePolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeRulesTest {

    private static final String SHOP = "shop";

    @TempDir
    private Path directory;

    @Test
    void valueTypesMustBeImmutable() {
        DesignGuard guard = new CompiledProject(directory)
                .add("Money", "package shop;\n\npublic class Money {\n    private long cents;\n    private final String currency = \"EUR\";\n"
                        + "    public void add(long more) {\n        cents += more;\n    }\n}\n")
                .add("Client", "package shop;\n\npublic class Client {\n    private java.net.http.HttpClient http;\n}\n")
                .add("Status", "package shop;\n\npublic enum Status {\n    NEW;\n    private int hits;\n}\n")
                .add("Row", "package shop;\n\n@Entity\npublic class Row {\n    private long id;\n}\n")
                .add("Entity", "package shop;\n\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface Entity {\n}\n")
                .guard(SHOP);

        assertThat(guard.types().valueTypesAreImmutable().evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("immutable-values");
            assertThat(violation.message()).isEqualTo("Money is a value, but its field 'cents' isn't final");
            assertThat(violation.fix()).isEqualTo("make the field final and return a new Money instead of changing it,"
                    + " or turn Money into a record");
        });
    }

    @Test
    void closedHierarchiesMustBeSealed() {
        DesignGuard guard = new CompiledProject(directory)
                .add("Shape", "package shop;\n\npublic interface Shape {\n    double area();\n    String name();\n}\n")
                .add("Circle", "package shop;\n\npublic record Circle(double r) implements Shape {\n"
                        + "    public double area() {\n        return r * r;\n    }\n    public String name() {\n        return \"circle\";\n    }\n}\n")
                .add("Square", "package shop;\n\npublic record Square(double side) implements Shape {\n"
                        + "    public double area() {\n        return side * side;\n    }\n    public String name() {\n        return \"square\";\n    }\n}\n")
                .add("Closed", "package shop;\n\npublic sealed interface Closed permits Only {\n}\n")
                .add("Only", "package shop;\n\npublic record Only() implements Closed {\n}\n")
                .add("Handler", "package shop;\n\npublic interface Handler {\n    void handle(String event);\n}\n")
                .add("Logger", "package shop;\n\npublic class Logger implements Handler {\n"
                        + "    public void handle(String event) {\n        event.length();\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.types().closedHierarchiesAreSealed().evaluate()).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("sealed-hierarchies");
            assertThat(violation.message()).isEqualTo("Shape is only implemented in this project, but isn't sealed");
            assertThat(violation.fix()).isEqualTo("declare it sealed: sealed interface Shape permits Circle, Square");
        });
    }

    @Test
    void finalByDefaultFlagsClassesNothingExtends() {
        DesignGuard guard = inheritanceShop();

        assertThat(guard.types().inheritance(InheritancePolicy.FINAL_BY_DEFAULT).evaluate())
                .extracting(Violation::message).containsExactly(
                        "Leaf isn't final, and nothing extends it",
                        "Service isn't final, and nothing extends it");
    }

    @Test
    void abstractOnlyFlagsExtendingAConcreteClass() {
        DesignGuard guard = inheritanceShop();

        assertThat(guard.types().inheritance(InheritancePolicy.ABSTRACT_ONLY).evaluate())
                .extracting(Violation::message).containsExactly(
                        "Leaf extends the concrete class Middle; extend only abstract classes",
                        "Middle extends the concrete class Base; extend only abstract classes");
    }

    @Test
    void maxDepthLimitsHierarchyDepth() {
        DesignGuard guard = inheritanceShop();

        assertThat(guard.types().inheritance(InheritancePolicy.ANY).maxDepth(1).evaluate())
                .extracting(Violation::message).containsExactly(
                        "Leaf is 2 classes deep in its inheritance hierarchy (max 1)");
    }

    private DesignGuard inheritanceShop() {
        return new CompiledProject(directory)
                .add("Base", "package shop;\n\npublic class Base {\n}\n")
                .add("Middle", "package shop;\n\npublic class Middle extends Base {\n}\n")
                .add("Leaf", "package shop;\n\npublic class Leaf extends Middle {\n}\n")
                .add("Service", "package shop;\n\npublic class Service {\n}\n")
                .add("Sealed", "package shop;\n\npublic final class Sealed {\n}\n")
                .guard(SHOP);
    }
}
