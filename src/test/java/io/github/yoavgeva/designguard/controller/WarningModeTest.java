package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rules turned into warnings pass and log their violations instead, through {@code System.Logger}; read here
 * through its default backend, {@code java.util.logging}.
 */
class WarningModeTest {

    private static final Logger LOG = Logger.getLogger("io.github.yoavgeva.designguard");
    private static final String NO_STUBS = "[no-stubs]";
    private static final String NO_SUPPRESSIONS = "no-suppressions";

    @TempDir
    private static Path directory;

    private static DesignGuard guard;

    private final Recorder recorder = new Recorder();

    @BeforeAll
    static void compile() {
        guard = LegacyShop.write(new CompiledProject(directory)).configure("shop.legacy")
                .freezingIn(directory.resolve("frozen"))
                .build();
    }

    @BeforeEach
    void record() {
        LOG.addHandler(recorder);
    }

    @AfterEach
    void stopRecording() {
        LOG.removeHandler(recorder);
    }

    @Test
    void aWarningRulePassesAndLogsItsViolations() {
        assertThatCode(() -> guard.guardrails().noStubs().asWarning().check()).doesNotThrowAnyException();

        assertThat(recorder.records).singleElement().satisfies(warning -> {
            assertThat(warning.getLevel()).isEqualTo(Level.WARNING);
            assertThat(warning.getMessage()).startsWith("1 design warning:\n").contains("warning " + NO_STUBS);
        });
    }

    @Test
    void warningsAreTheRulesViolationsWithTheWarningSeverity() {
        List<Violation> errors = guard.guardrails().noStubs().evaluate();

        assertThat(guard.guardrails().noStubs().asWarning().evaluate())
                .hasSameSizeAs(errors)
                .allSatisfy(warning -> assertThat(warning.severity()).isEqualTo(Violation.Severity.WARNING))
                .extracting(Violation::message).containsExactlyElementsOf(errors.stream().map(Violation::message).toList());
    }

    @Test
    void aWarningRuleKeepsWarningAsItIsConfigured() {
        BasicRule rule = guard.guardrails().noStubs().asWarning().that().resideInAPackage("..misc..");

        assertThatCode(rule::check).doesNotThrowAnyException();
        assertThat(rule.excluding("shop.legacy..").evaluate()).isEmpty();
        assertThat(recorder.records).singleElement().extracting(LogRecord::getMessage).asString().contains(NO_STUBS);
    }

    @Test
    void aRuleSetFailsOnItsErrorsAndLogsTheRulesItWarnsOn() {
        RuleSet rules = guard.recommended().warningOn(NO_SUPPRESSIONS);

        assertThatThrownBy(rules::check).isInstanceOf(AssertionError.class)
                .hasMessageContaining(NO_STUBS).hasMessageNotContaining(NO_SUPPRESSIONS);
        assertThat(recorder.records).singleElement().extracting(LogRecord::getMessage).asString()
                .contains("warning [" + NO_SUPPRESSIONS + "]").doesNotContain(NO_STUBS);
    }

    @Test
    void aRuleSetOfWarningsPasses() {
        assertThatCode(() -> guard.recommended().asWarning().check()).doesNotThrowAnyException();
        assertThat(recorder.records).singleElement().extracting(LogRecord::getMessage).asString()
                .startsWith(guard.recommended().evaluate().size() + " design warnings:\n");
    }

    @Test
    void warningOnRefusesIdsTheSetDoesntHold() {
        assertThatThrownBy(() -> guard.recommended().warningOn("no-stub"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("the rule set has no rule no-stub; its rules are ");
    }

    @Test
    void aFrozenWarningRuleLogsInsteadOfFailing() {
        FrozenRule frozen = guard.guardrails().noStubs().asWarning().frozen("stub-warnings");

        assertThatCode(frozen::check).doesNotThrowAnyException();
        assertThat(Files.exists(directory.resolve("frozen/stub-warnings.txt"))).isTrue();
        assertThat(recorder.records).singleElement().extracting(LogRecord::getMessage).asString().startsWith("Recorded ");
    }

    /** Keeps the records logged while a test runs. */
    private static final class Recorder extends Handler {

        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord logged) {
            records.add(logged);
        }

        @Override
        public void flush() {
            // records are kept in memory
        }

        @Override
        public void close() {
            records.clear();
        }
    }
}
