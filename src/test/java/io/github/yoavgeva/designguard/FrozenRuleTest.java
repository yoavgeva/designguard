package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.model.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Adopting a rule on code that already breaks it: the violations it has when frozen are recorded, and only new ones fail. */
class FrozenRuleTest {

    private static final String SHOP = "shop";
    private static final String FROZEN = "frozen";
    private static final String LAYERS = "layers";
    private static final String CONTROLLER = "OrderController";
    private static final String REPOSITORY = "OrderRepository";
    private static final String ARCHIVE = "OrderArchive";
    private static final String CONTROLLER_SOURCE = "package shop.web;\n\npublic class OrderController {\n}\n";
    private static final String REPOSITORY_SOURCE = """
            package shop.persistence;

            public class OrderRepository {
                public String notify(shop.web.OrderController controller) {
                    return controller.toString();
                }
            }
            """;
    private static final String ARCHIVE_SOURCE = """
            package shop.persistence;

            public class OrderArchive {
                public String archive(shop.web.OrderController controller) {
                    return controller.toString();
                }
            }
            """;
    private static final String CLEAN_ARCHIVE_SOURCE = """
            package shop.persistence;

            public class OrderArchive {
                public String archive(String order) {
                    return order;
                }
            }
            """;

    @TempDir
    private Path directory;

    @Test
    void theFirstRunRecordsTheViolationsAndFailsOnce() throws IOException {
        CompiledProject project = new CompiledProject(directory).add(CONTROLLER, CONTROLLER_SOURCE)
                .add(REPOSITORY, REPOSITORY_SOURCE);

        assertThatThrownBy(() -> frozenLayers(project).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("Recorded 1 design violation in " + directory.resolve(FROZEN).resolve("layers.txt")
                        + ". Commit the file: from now on, only new violations fail.\n")
                .hasMessageContaining("[layered-architecture] OrderRepository (layer Persistence) depends on"
                        + " OrderController (layer Web)");
        assertThat(recorded(LAYERS)).containsExactly(directory.resolve("src/shop/persistence/OrderRepository.java")
                + ": [layered-architecture] OrderRepository (layer Persistence) depends on OrderController (layer Web),"
                + " which Persistence may not use");

        frozenLayers(project).check();
    }

    @Test
    void laterRunsFailOnlyOnNewViolations() {
        CompiledProject project = new CompiledProject(directory).add(CONTROLLER, CONTROLLER_SOURCE)
                .add(REPOSITORY, REPOSITORY_SOURCE);
        assertThat(frozenLayers(project).evaluate()).hasSize(1);

        project.add(ARCHIVE, ARCHIVE_SOURCE);

        assertThatThrownBy(() -> frozenLayers(project).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("1 new design violation (1 frozen in "
                        + directory.resolve(FROZEN).resolve("layers.txt") + "):\n")
                .hasMessageContaining("OrderArchive (layer Persistence) depends on OrderController")
                .hasMessageNotContaining(REPOSITORY);
    }

    @Test
    void fixedViolationsDropOutOfTheRecordSoTheyCannotComeBack() throws IOException {
        CompiledProject project = new CompiledProject(directory).add(CONTROLLER, CONTROLLER_SOURCE)
                .add(REPOSITORY, REPOSITORY_SOURCE).add(ARCHIVE, ARCHIVE_SOURCE);
        assertThat(frozenLayers(project).evaluate()).hasSize(2);

        project.add(ARCHIVE, CLEAN_ARCHIVE_SOURCE);
        assertThat(frozenLayers(project).evaluate()).isEmpty();
        assertThat(recorded(LAYERS)).singleElement().asString().contains("OrderRepository (layer Persistence)");

        project.add(ARCHIVE, ARCHIVE_SOURCE);
        assertThat(frozenLayers(project).evaluate()).extracting(Violation::message).containsExactly(
                "OrderArchive (layer Persistence) depends on OrderController (layer Web), which Persistence may not use");
    }

    @Test
    void violationsThatMoveToAnotherLineStayFrozen() {
        CompiledProject project = new CompiledProject(directory).add(CONTROLLER, CONTROLLER_SOURCE)
                .add(REPOSITORY, REPOSITORY_SOURCE);
        frozenLayers(project).evaluate();

        project.add(REPOSITORY, REPOSITORY_SOURCE.replace("public String notify", "public int size() {\n"
                + "        return 0;\n    }\n\n    public String notify"));

        assertThat(frozenLayers(project).evaluate()).isEmpty();
    }

    @Test
    void eachRecordedViolationAcceptsOneOccurrence() {
        String stub = "package shop;\n\npublic class Orders {\n    // TODO later\n    public int count() {\n"
                + "        return 1;\n    }\n}\n";
        CompiledProject project = new CompiledProject(directory).add("Orders", stub);
        assertThat(guard(project).guardrails().noStubs().frozen("stubs").evaluate()).hasSize(1);

        project.add("Orders", stub.replace("}\n}", "}\n\n    // TODO later\n    public int total() {\n"
                + "        return 2;\n    }\n}"));

        assertThat(guard(project).guardrails().noStubs().frozen("stubs").evaluate())
                .extracting(Violation::line, Violation::message)
                .containsExactly(tuple(9, "TODO left in the code: TODO later"));
    }

    @Test
    void recordsNameFilesRelativeToTheWorkingDirectorySoEveryMachineMatchesThem() throws IOException {
        DesignGuard guard = DesignGuard.forPackages("io.github.yoavgeva.designguard")
                .withSourceRoots(Path.of("src", "main", "java").toAbsolutePath())
                .freezingIn(directory.resolve(FROZEN))
                .build();

        guard.api().minimalVisibility().frozen("visibility").evaluate();

        assertThat(recorded("visibility")).isNotEmpty()
                .allSatisfy(line -> assertThat(line).startsWith("src/main/java/io/github/yoavgeva/designguard/"));
    }

    @Test
    void namesMustBePlainFileNames() {
        DesignRule<?> rule = new DesignGuard(SHOP).guardrails().noStubs();

        assertThatThrownBy(() -> rule.frozen("../layers"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name a frozen rule with letters, digits, '.', '_' and '-', starting with a letter or digit;"
                        + " was ../layers");
    }

    private DesignGuard guard(CompiledProject project) {
        return project.configure(SHOP).freezingIn(directory.resolve(FROZEN)).build();
    }

    private FrozenRule frozenLayers(CompiledProject project) {
        return guard(project).architecture().layered().layer("Web", "..web..").layer("Persistence", "..persistence..")
                .frozen(LAYERS);
    }

    private List<String> recorded(String name) throws IOException {
        return Files.readAllLines(directory.resolve(FROZEN).resolve(name + ".txt")).stream()
                .filter(line -> !line.startsWith("#"))
                .toList();
    }
}
