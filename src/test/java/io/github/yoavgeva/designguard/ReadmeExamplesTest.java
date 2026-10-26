package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.RuleFiltersContractTest.NamedRule;
import io.github.yoavgeva.designguard.model.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The README shows every rule with code it flags and the same code fixed. Both are compiled here and the rule runs on
 * them, so the README can't show what a rule doesn't do. A new rule needs an example there.
 */
class ReadmeExamplesTest {

    private static final String EVERY_RULE = "io.github.yoavgeva.designguard.RuleFiltersContractTest#rules";

    private static Map<String, ReadmeExample> examples;

    @BeforeAll
    static void read() throws IOException {
        examples = ReadmeExample.readAll(Files.readString(Path.of("README.md")));
    }

    @Test
    void showsEveryRule() {
        assertThat(examples.keySet()).containsExactlyInAnyOrderElementsOf(
                RuleFiltersContractTest.EVERY_RULE.stream().map(NamedRule::name).toList());
    }

    @Test
    void marksTheRecommendedRules() {
        assertThat(examples.values().stream().filter(ReadmeExample::recommended).map(ReadmeExample::id))
                .containsExactlyInAnyOrderElementsOf(DesignGuard.forPackages("shop").build().recommended().rules());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(EVERY_RULE)
    void flagsTheCodeItShows(String name, Function<DesignGuard, DesignRule<?>> rule, @TempDir Path directory) {
        assertThat(examples).containsKey(name);
        ReadmeExample example = examples.get(name);

        assertThat(rule.apply(example.flaggedCode(directory)).evaluate())
                .isNotEmpty()
                .extracting(Violation::rule).containsOnly(example.id());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(EVERY_RULE)
    void passesTheFixedCode(String name, Function<DesignGuard, DesignRule<?>> rule, @TempDir Path directory) {
        assertThat(examples).containsKey(name);

        assertThat(rule.apply(examples.get(name).fixedCode(directory)).evaluate()).isEmpty();
    }
}
