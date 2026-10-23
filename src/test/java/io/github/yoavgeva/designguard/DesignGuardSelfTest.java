package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.controller.DesignGuard;
import io.github.yoavgeva.designguard.controller.DesignRule;
import io.github.yoavgeva.designguard.model.InheritancePolicy;
import io.github.yoavgeva.designguard.model.TypeDispatchPolicy;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** designguard holds itself to its own rules: every rule here passes on this repository. */
class DesignGuardSelfTest {

    private static final DesignGuard GUARD = new DesignGuard("io.github.yoavgeva.designguard");

    static Stream<Arguments> rules() {
        return Stream.of(
                Arguments.of("model-view-controller layers", GUARD.architecture().mvc()),
                Arguments.of("clock is injected", GUARD.dependencies().clockIsInjected()),
                Arguments.of("randomness is injected", GUARD.dependencies().randomnessIsInjected()),
                Arguments.of("configuration is injected", GUARD.dependencies().configurationIsInjected()),
                Arguments.of("no raw threads", GUARD.dependencies().noRawThreads()),
                Arguments.of("stable dependencies", GUARD.dependencies().stableDependencies()),
                Arguments.of("type dispatch only over sealed types", GUARD.types().typeDispatch(TypeDispatchPolicy.SEALED_ONLY)),
                Arguments.of("records are immutable", GUARD.types().recordsAreImmutable()),
                Arguments.of("data classes are records", GUARD.types().dataClassesAreRecords()),
                Arguments.of("values are immutable", GUARD.types().valueTypesAreImmutable()),
                Arguments.of("classes are final by default", GUARD.types().inheritance(InheritancePolicy.FINAL_BY_DEFAULT)),
                Arguments.of("no flag arguments", GUARD.api().noFlagArguments()),
                Arguments.of("no checked exceptions", GUARD.api().noCheckedExceptions()),
                Arguments.of("no stubs", GUARD.guardrails().noStubs()),
                Arguments.of("no suppressions", GUARD.guardrails().noSuppressions()),
                Arguments.of("tests assert meaningfully", GUARD.guardrails().testsAssertMeaningfully()),
                Arguments.of("no disabled tests", GUARD.guardrails().noDisabledTests()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void holds(String name, DesignRule<?> rule) {
        rule.check();
    }
}
