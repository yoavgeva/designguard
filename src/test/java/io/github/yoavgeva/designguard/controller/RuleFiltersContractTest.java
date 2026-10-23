package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.InheritancePolicy;
import io.github.yoavgeva.designguard.model.InterfacePolicy;
import io.github.yoavgeva.designguard.model.TypeDispatchPolicy;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every rule honours {@code that()}, {@code excluding()} and {@code frozen()}: a legacy shop breaks each rule at least
 * once, all in {@code shop.legacy}. A new rule belongs in {@link #rules()}, with code here that breaks it.
 */
class RuleFiltersContractTest {

    private static final String WEB = "..web..";
    private static final String DOMAIN = "..domain..";

    @TempDir
    private static Path directory;

    private static DesignGuard guard;

    @BeforeAll
    static void compile() {
        guard = LegacyShop.write(new CompiledProject(directory)).configure("shop.legacy")
                .freezingIn(directory.resolve("frozen"))
                .build();
    }

    static Stream<Arguments> rules() {
        return Stream.of(
                rule("layered", g -> g.architecture().layered().layer("Web", WEB).layer("Persistence", "..persistence..")),
                rule("mvc", g -> g.architecture().mvc()),
                rule("hexagonal", g -> g.architecture().hexagonal().adapter("web", WEB)),
                rule("onion", g -> g.architecture().onion()),
                rule("featureSlices", g -> g.architecture().featureSlices()),
                rule("frameworksStayOutOf", g -> g.architecture().frameworksStayOutOf(DOMAIN)),
                rule("interfacePolicy", g -> g.dependencies().interfacePolicy(InterfacePolicy.SERVICES)),
                rule("injectServices", g -> g.dependencies().injectServices()),
                rule("clockIsInjected", g -> g.dependencies().clockIsInjected()),
                rule("randomnessIsInjected", g -> g.dependencies().randomnessIsInjected()),
                rule("configurationIsInjected", g -> g.dependencies().configurationIsInjected()),
                rule("noRawThreads", g -> g.dependencies().noRawThreads()),
                rule("constructorsDoNoWork", g -> g.dependencies().constructorsDoNoWork()),
                rule("stableDependencies", g -> g.dependencies().stableDependencies()),
                rule("infrastructureExceptionsStayOutOf", g -> g.dependencies().infrastructureExceptionsStayOutOf(DOMAIN)),
                rule("entitiesStayOutOf", g -> g.dependencies().entitiesStayOutOf(WEB)),
                rule("typeDispatch", g -> g.types().typeDispatch(TypeDispatchPolicy.SEALED_ONLY)),
                rule("recordsAreImmutable", g -> g.types().recordsAreImmutable()),
                rule("dataClassesAreRecords", g -> g.types().dataClassesAreRecords()),
                rule("valueTypesAreImmutable", g -> g.types().valueTypesAreImmutable()),
                rule("servicesAreStateless", g -> g.types().servicesAreStateless()),
                rule("typedIdentifiers", g -> g.types().typedIdentifiers(DOMAIN)),
                rule("enumsOverConstants", g -> g.types().enumsOverConstants()),
                rule("closedHierarchiesAreSealed", g -> g.types().closedHierarchiesAreSealed()),
                rule("inheritance", g -> g.types().inheritance(InheritancePolicy.FINAL_BY_DEFAULT)),
                rule("noFlagArguments", g -> g.api().noFlagArguments()),
                rule("packagesAreNullMarked", g -> g.api().packagesAreNullMarked()),
                rule("optionalUsage", g -> g.api().optionalUsage()),
                rule("noCheckedExceptions", g -> g.api().noCheckedExceptions()),
                rule("noEffectfulStaticMethods", g -> g.api().noEffectfulStaticMethods()),
                rule("typedApis", g -> g.api().typedApis()),
                rule("accessorsChangeNothing", g -> g.api().accessorsChangeNothing()),
                rule("minimalVisibility", g -> g.api().minimalVisibility()),
                rule("noStubs", g -> g.guardrails().noStubs()),
                rule("noSuppressions", g -> g.guardrails().noSuppressions()),
                rule("testsAssertMeaningfully", g -> g.guardrails().testsAssertMeaningfully()),
                rule("noDisabledTests", g -> g.guardrails().noDisabledTests()),
                rule("serviceLikeClassesHaveTests", g -> g.guardrails().serviceLikeClassesHaveTests()),
                rule("noWidenedForTests", g -> g.guardrails().noWidenedForTests()),
                rule("testsCheckResults", g -> g.guardrails().testsCheckResults()),
                rule("testsUseTheirSubject", g -> g.guardrails().testsUseTheirSubject()),
                rule("testsDontMockValues", g -> g.guardrails().testsDontMockValues()),
                rule("noUnusedPublicCode", g -> g.guardrails().noUnusedPublicCode()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void packageFiltersSkipEveryViolation(String name, Function<DesignGuard, DesignRule<?>> rule) {
        DesignRule<?> configured = rule.apply(guard);

        assertThat(configured.evaluate()).as("the legacy shop breaks %s", name).isNotEmpty();
        assertThat(configured.excluding("..legacy..").evaluate()).isEmpty();
        assertThat(configured.that().resideInAPackage("..elsewhere..").evaluate()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void frozenRulesRecordOnceThenPass(String name, Function<DesignGuard, DesignRule<?>> rule) {
        assertThatThrownBy(rule.apply(guard).frozen(name)::check)
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("Recorded ");
        assertThatCode(rule.apply(guard).frozen(name)::check).doesNotThrowAnyException();
    }

    private static Arguments rule(String name, Function<DesignGuard, DesignRule<?>> rule) {
        return Arguments.of(name, rule);
    }
}
