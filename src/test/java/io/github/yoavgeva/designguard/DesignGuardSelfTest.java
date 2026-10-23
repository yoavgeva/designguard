package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.controller.DesignGuard;
import io.github.yoavgeva.designguard.controller.DesignRule;
import io.github.yoavgeva.designguard.model.InheritancePolicy;
import io.github.yoavgeva.designguard.model.InterfacePolicy;
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
                Arguments.of("no class cycles", GUARD.architecture().classesHaveNoCycles()),
                Arguments.of("no frameworks in the model", GUARD.architecture().frameworksStayOutOf("..model..")),
                Arguments.of("clock is injected", GUARD.dependencies().clockIsInjected()),
                Arguments.of("randomness is injected", GUARD.dependencies().randomnessIsInjected()),
                Arguments.of("configuration is injected", GUARD.dependencies().configurationIsInjected()),
                Arguments.of("no raw threads", GUARD.dependencies().noRawThreads()),
                Arguments.of("services are injected", GUARD.dependencies().injectServices()),
                Arguments.of("constructors do no work", GUARD.dependencies().constructorsDoNoWork()),
                Arguments.of("services are interfaces", GUARD.dependencies().interfacePolicy(InterfacePolicy.SERVICES)),
                Arguments.of("stable dependencies", GUARD.dependencies().stableDependencies()),
                Arguments.of("type dispatch only over sealed types", GUARD.types().typeDispatch(TypeDispatchPolicy.SEALED_ONLY)),
                Arguments.of("records are immutable", GUARD.types().recordsAreImmutable()),
                Arguments.of("data classes are records", GUARD.types().dataClassesAreRecords()),
                Arguments.of("values are immutable", GUARD.types().valueTypesAreImmutable()),
                Arguments.of("services are stateless", GUARD.types().servicesAreStateless()),
                Arguments.of("enums over constants", GUARD.types().enumsOverConstants()),
                Arguments.of("typed identifiers in the model", GUARD.types().typedIdentifiers("..model..")),
                Arguments.of("closed hierarchies are sealed", GUARD.types().closedHierarchiesAreSealed()),
                Arguments.of("classes are final by default", GUARD.types().inheritance(InheritancePolicy.FINAL_BY_DEFAULT)),
                Arguments.of("no flag arguments", GUARD.api().noFlagArguments()),
                Arguments.of("no checked exceptions", GUARD.api().noCheckedExceptions()),
                Arguments.of("packages are null-marked", GUARD.api().packagesAreNullMarked()),
                Arguments.of("optional only as a return type", GUARD.api().optionalUsage()),
                Arguments.of("no effectful static methods", GUARD.api().noEffectfulStaticMethods()),
                Arguments.of("typed APIs", GUARD.api().typedApis()),
                Arguments.of("accessors change nothing", GUARD.api().accessorsChangeNothing()),
                Arguments.of("no stubs", GUARD.guardrails().noStubs()),
                Arguments.of("no suppressions", GUARD.guardrails().noSuppressions()),
                Arguments.of("tests assert meaningfully", GUARD.tests().testsAssertMeaningfully()),
                Arguments.of("tests don't mock values", GUARD.tests().testsDontMockValues()),
                Arguments.of("tests use their subject", GUARD.tests().testsUseTheirSubject()),
                Arguments.of("tests check results", GUARD.tests().testsCheckResults()),
                Arguments.of("nothing widened for tests", GUARD.guardrails().noWidenedForTests()),
                Arguments.of("no disabled tests", GUARD.tests().noDisabledTests()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void holds(String name, DesignRule<?> rule) {
        rule.check();
    }
}
