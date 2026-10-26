package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yoavgeva.designguard.model.InheritancePolicy;
import io.github.yoavgeva.designguard.model.InterfacePolicy;
import io.github.yoavgeva.designguard.model.TypeDispatchPolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every rule honours {@code that()}, {@code excluding()} and {@code frozen()}: a legacy shop breaks each rule at least
 * once, all in {@code shop.legacy}. A new rule belongs in {@link #EVERY_RULE}, with code here that breaks it.
 */
class RuleFiltersContractTest {

    private static final String WEB = "..web..";
    private static final String DOMAIN = "..domain..";
    private static final Pattern RULE_HEADING = Pattern.compile("^### `([a-z-]+)`$", Pattern.MULTILINE);

    /** Every rule, with code in the legacy shop that breaks it. */
    static final List<NamedRule> EVERY_RULE = List.of(
            rule("layered", g -> g.architecture().layered().layer("Web", WEB).layer("Persistence", "..persistence..")),
            rule("mvc", g -> g.architecture().mvc()),
            rule("hexagonal", g -> g.architecture().hexagonal().adapter("web", WEB)),
            rule("onion", g -> g.architecture().onion()),
            rule("featureSlices", g -> g.architecture().featureSlices()),
            rule("classesHaveNoCycles", g -> g.architecture().classesHaveNoCycles()),
            rule("adaptersStayThin", g -> g.architecture().adaptersStayThin(WEB)),
            rule("frameworksStayOutOf", g -> g.architecture().frameworksStayOutOf(DOMAIN)),
            rule("interfacePolicy", g -> g.dependencies().interfacePolicy(InterfacePolicy.SERVICES)),
            rule("injectServices", g -> g.dependencies().injectServices()),
            rule("clockIsInjected", g -> g.dependencies().clockIsInjected()),
            rule("randomnessIsInjected", g -> g.dependencies().randomnessIsInjected()),
            rule("configurationIsInjected", g -> g.dependencies().configurationIsInjected()),
            rule("noRawThreads", g -> g.dependencies().noRawThreads()),
            rule("constructorsDoNoWork", g -> g.dependencies().constructorsDoNoWork()),
            rule("noQueriesInLoops", g -> g.dependencies().noQueriesInLoops()),
            rule("noIoInTransactions", g -> g.dependencies().noIoInTransactions()),
            rule("stableDependencies", g -> g.dependencies().stableDependencies()),
            rule("infrastructureExceptionsStayOutOf", g -> g.architecture().infrastructureExceptionsStayOutOf(DOMAIN)),
            rule("entitiesStayOutOf", g -> g.architecture().entitiesStayOutOf(WEB)),
            rule("typeDispatch", g -> g.types().typeDispatch(TypeDispatchPolicy.SEALED_ONLY)),
            rule("recordsAreImmutable", g -> g.types().recordsAreImmutable()),
            rule("dataClassesAreRecords", g -> g.types().dataClassesAreRecords()),
            rule("valueTypesAreImmutable", g -> g.types().valueTypesAreImmutable()),
            rule("servicesAreStateless", g -> g.types().servicesAreStateless()),
            rule("typedIdentifiers", g -> g.types().typedIdentifiers(DOMAIN)),
            rule("enumsOverConstants", g -> g.types().enumsOverConstants()),
            rule("interfacesAreFocused", g -> g.types().interfacesAreFocused()),
            rule("closedHierarchiesAreSealed", g -> g.types().closedHierarchiesAreSealed()),
            rule("inheritance", g -> g.types().inheritance(InheritancePolicy.FINAL_BY_DEFAULT)),
            rule("noFlagArguments", g -> g.api().noFlagArguments()),
            rule("packagesAreNullMarked", g -> g.api().packagesAreNullMarked()),
            rule("optionalUsage", g -> g.api().optionalUsage()),
            rule("noCheckedExceptions", g -> g.api().noCheckedExceptions()),
            rule("noEffectfulStaticMethods", g -> g.api().noEffectfulStaticMethods()),
            rule("typedApis", g -> g.api().typedApis()),
            rule("noStringlyTypedChoices", g -> g.api().noStringlyTypedChoices()),
            rule("accessorsChangeNothing", g -> g.api().accessorsChangeNothing()),
            rule("minimalVisibility", g -> g.api().minimalVisibility()),
            rule("noStubs", g -> g.guardrails().noStubs()),
            rule("noSuppressions", g -> g.guardrails().noSuppressions()),
            rule("testsAssertMeaningfully", g -> g.tests().testsAssertMeaningfully()),
            rule("noDisabledTests", g -> g.tests().noDisabledTests()),
            rule("serviceLikeClassesHaveTests", g -> g.guardrails().serviceLikeClassesHaveTests()),
            rule("noWidenedForTests", g -> g.guardrails().noWidenedForTests()),
            rule("testsCheckResults", g -> g.tests().testsCheckResults()),
            rule("testsHaveNoLogic", g -> g.tests().testsHaveNoLogic()),
            rule("testsUseAFixedClock", g -> g.tests().testsUseAFixedClock()),
            rule("testsUseSeededRandomness", g -> g.tests().testsUseSeededRandomness()),
            rule("testsDontReadTheEnvironment", g -> g.tests().testsDontReadTheEnvironment()),
            rule("testsDontSleep", g -> g.tests().testsDontSleep()),
            rule("testsUseTheirSubject", g -> g.tests().testsUseTheirSubject()),
            rule("testsDontMockValues", g -> g.tests().testsDontMockValues()),
            rule("noUnusedPublicCode", g -> g.guardrails().noUnusedPublicCode()),
            rule("noSwallowedErrors", g -> g.guardrails().noSwallowedErrors()),
            rule("writesAreAuthorized", g -> g.guardrails().writesAreAuthorized()),
            rule("noUnboundedWaits", g -> g.guardrails().noUnboundedWaits()),
            rule("readsAreAuthorized", g -> g.guardrails().readsAreAuthorized()),
            rule("noMassAssignment", g -> g.data().noMassAssignment()),
            rule("noLostUpdates", g -> g.data().noLostUpdates()),
            rule("noFloatingPointMoney", g -> g.data().noFloatingPointMoney()),
            rule("noUnboundedQueries", g -> g.data().noUnboundedQueries()));

    @TempDir
    private static Path directory;

    private static DesignGuard guard;

    @BeforeAll
    static void compile() {
        guard = LegacyShop.write(new CompiledProject(directory)).configure("shop.legacy")
                .freezingIn(directory.resolve("frozen"))
                .build();
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void theRuleDocsDescribeItUnderItsId(String name, Function<DesignGuard, DesignRule<?>> rule) throws IOException {
        Set<String> documented = documentedRules();

        assertThat(rule.apply(guard).evaluate()).extracting(Violation::rule).allSatisfy(id ->
                assertThat(documented).as("docs/rules.md has a section for %s, the id of %s", id, name).contains(id));
    }

    @Test
    void theRuleDocsDescribeNoOtherRules() throws IOException {
        assertThat(documentedRules()).hasSize(Math.toIntExact(rules().count()));
    }

    /** The rule ids docs/rules.md has a section for: a heading like {@code ### `no-stubs`}. */
    private static Set<String> documentedRules() throws IOException {
        return RULE_HEADING.matcher(Files.readString(Path.of("docs/rules.md"))).results()
                .map(heading -> heading.group(1))
                .collect(Collectors.toSet());
    }

    static Stream<Arguments> rules() {
        return EVERY_RULE.stream().map(rule -> Arguments.of(rule.name(), rule.of()));
    }

    private static NamedRule rule(String name, Function<DesignGuard, DesignRule<?>> rule) {
        return new NamedRule(name, rule);
    }

    /**
     * A rule, as a test names it.
     *
     * @param name the rule's method
     * @param of   the rule, configured for the legacy shop
     */
    record NamedRule(String name, Function<DesignGuard, DesignRule<?>> of) {
    }
}
