package io.github.yoavgeva.designguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.ProjectSettings;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A call may run its target or any override, found once per target however many calls share it. */
class OverridesTest {

    private static final String SHOP = "shop";

    @TempDir
    private Path directory;

    @Test
    void findsTheOverridesOfEachCallTargetOnce() {
        new CompiledProject(directory)
                .add("Shape", "package shop;\n\npublic interface Shape {\n    double area();\n}\n")
                .add("Circle", "package shop;\n\npublic class Circle implements Shape {\n    public double area() {\n"
                        + "        return 3.14;\n    }\n}\n")
                .add("Square", "package shop;\n\npublic class Square implements Shape {\n    public double area() {\n"
                        + "        return 1;\n    }\n}\n")
                .add("Report", "package shop;\n\npublic class Report {\n    public double total(Shape shape) {\n"
                        + "        return shape.area();\n    }\n}\n")
                .guard(SHOP);
        Overrides overrides = new Overrides();
        CodeUnitAccessTarget area = callOfReport();

        assertThat(overrides.of(area))
                .containsExactlyInAnyOrder("shop.Shape.area()", "shop.Circle.area()", "shop.Square.area()")
                .isSameAs(overrides.of(area));
    }

    @Test
    void aCallToAnInheritedMethodNamesTheMethodASuperclassDeclares() {
        new CompiledProject(directory)
                .add("Base", "package shop;\n\npublic abstract class Base {\n    public String name() {\n"
                        + "        return \"base\";\n    }\n}\n")
                .add("Leaf", "package shop;\n\npublic final class Leaf extends Base {\n}\n")
                .add("Report", "package shop;\n\npublic class Report {\n    public String title(Leaf leaf) {\n"
                        + "        return leaf.name();\n    }\n}\n")
                .guard(SHOP);
        CodeUnitAccessTarget name = callOfReport();

        assertThat(new Overrides().named(name)).containsExactlyInAnyOrder("shop.Leaf.name()", "shop.Base.name()");
    }

    /** The one call the class {@code Report} makes. */
    private CodeUnitAccessTarget callOfReport() {
        ProjectClasses classes = CodeBase.of(ProjectSettings.forPackages(List.of(SHOP))
                .withClassDirectories(List.of(directory.resolve("classes")))).projectClasses();
        return classes.stream().filter(type -> "Report".equals(type.getSimpleName())).findFirst()
                .orElseThrow().getMethodCallsFromSelf().iterator().next().getTarget();
    }
}
