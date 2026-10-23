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
                .guard("shop");
        ProjectClasses classes = CodeBase.of(ProjectSettings.forPackages(List.of("shop"))
                .withClassDirectories(List.of(directory.resolve("classes")))).projectClasses();
        Overrides overrides = new Overrides();
        CodeUnitAccessTarget area = classes.stream().filter(type -> "Report".equals(type.getSimpleName())).findFirst()
                .orElseThrow().getMethodCallsFromSelf().iterator().next().getTarget();

        assertThat(overrides.of(area))
                .containsExactlyInAnyOrder("shop.Shape.area()", "shop.Circle.area()", "shop.Square.area()")
                .isSameAs(overrides.of(area));
    }
}
