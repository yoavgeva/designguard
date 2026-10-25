package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.yoavgeva.designguard.model.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A rule set, here {@link DesignGuard#recommended()}, runs its rules as one: one report, one filter, one frozen record. */
class RuleSetTest {

    private static final String NO_SUPPRESSIONS = "no-suppressions";
    private static final String NO_SLEEP = "tests-no-sleep";
    private static final Comparator<Violation> BY_LOCATION =
            Comparator.comparing(Violation::file).thenComparingInt(Violation::line);

    @TempDir
    private static Path directory;

    private static DesignGuard guard;

    @BeforeAll
    static void compile() {
        guard = LegacyShop.write(new CompiledProject(directory)).configure("shop.legacy")
                .freezingIn(directory.resolve("frozen"))
                .build();
    }

    @Test
    void reportsWhatEachOfItsRulesReportsOrderedByLocation() {
        List<Violation> separately = Stream.<DesignRule<?>>of(
                        guard.guardrails().noStubs(), guard.guardrails().noSuppressions(),
                        guard.guardrails().noWidenedForTests(),
                        guard.tests().testsAssertMeaningfully(), guard.tests().noDisabledTests(),
                        guard.tests().testsCheckResults(), guard.tests().testsUseTheirSubject(),
                        guard.tests().testsDontMockValues(), guard.tests().testsHaveNoLogic(),
                        guard.tests().testsUseAFixedClock(), guard.tests().testsUseSeededRandomness(),
                        guard.tests().testsDontReadTheEnvironment(), guard.tests().testsDontSleep(),
                        guard.architecture().classesHaveNoCycles(), guard.dependencies().noQueriesInLoops(),
                        guard.dependencies().constructorsDoNoWork(), guard.dependencies().noRawThreads(),
                        guard.api().accessorsChangeNothing(), guard.api().noFlagArguments(),
                        guard.api().noStringlyTypedChoices(), guard.api().optionalUsage(),
                        guard.types().enumsOverConstants(), guard.types().recordsAreImmutable(),
                        guard.types().interfacesAreFocused())
                .flatMap(rule -> rule.evaluate().stream())
                .toList();

        assertThat(guard.recommended().evaluate()).containsExactlyInAnyOrderElementsOf(separately)
                .isSortedAccordingTo(BY_LOCATION);
    }

    @Test
    void namesEachRuleByTheIdItsViolationsCarry() {
        RuleSet recommended = guard.recommended();

        assertThat(recommended.rules()).hasSize(24).doesNotHaveDuplicates();
        assertThat(recommended.evaluate().stream().map(Violation::rule).distinct())
                .containsExactlyInAnyOrderElementsOf(recommended.rules());
    }

    @Test
    void withoutLeavesOutRulesByTheirId() {
        RuleSet fewer = guard.recommended().without(NO_SUPPRESSIONS, NO_SLEEP);

        assertThat(fewer.rules()).hasSize(22).doesNotContain(NO_SUPPRESSIONS, NO_SLEEP);
        assertThat(fewer.evaluate()).isNotEmpty().extracting(Violation::rule).doesNotContain(NO_SUPPRESSIONS, NO_SLEEP);
    }

    @Test
    void withoutRefusesIdsTheSetDoesntHold() {
        assertThatThrownBy(() -> guard.recommended().without("no-stub"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("the rule set has no rule no-stub; its rules are no-stubs, no-suppressions, ");
    }

    @Test
    void selectionsApplyToEveryRule() {
        assertThat(guard.recommended().excluding("shop.legacy..").evaluate()).isEmpty();
        assertThat(guard.recommended().that().resideInAPackage("..zoo..").evaluate()).isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.file()).contains("/zoo/"));
    }

    @Test
    void checkFailsListingEveryViolation() {
        int violations = guard.recommended().evaluate().size();

        assertThatThrownBy(() -> guard.recommended().check())
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith(violations + " design violations:\n");
    }

    @Test
    void violationsFoundEarlierAreCheckedTheSameWay() {
        List<Violation> found = guard.recommended().evaluate();

        assertThatThrownBy(() -> Checkable.check(found))
                .isInstanceOf(AssertionError.class)
                .hasMessage(catchThrowable(() -> guard.recommended().check()).getMessage());
    }

    @Test
    void freezesEveryRuleInOneRecord() throws IOException {
        FrozenRule frozen = guard.recommended().frozen("recommended");

        assertThatThrownBy(frozen::check).isInstanceOf(AssertionError.class);
        assertThat(Files.readAllLines(directory.resolve("frozen/recommended.txt")))
                .filteredOn(line -> !line.startsWith("#"))
                .hasSize(guard.recommended().evaluate().size());
        assertThatCode(frozen::check).doesNotThrowAnyException();
    }
}
