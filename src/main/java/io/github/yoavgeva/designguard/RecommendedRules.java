package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.internal.CodeBase;
import io.github.yoavgeva.designguard.model.ClassFilter;
import java.util.List;

/**
 * The rules {@link DesignGuard#recommended()} runs: those that need no configuration, hold for any kind of project
 * and Java version, and were rarely wrong on real projects. Left out are the architecture styles, which need
 * configuring; the functional-core and dependency-injection style rules; the sealed-type and record rules, which need
 * Java 17; the rules that public library APIs break by design, such as unused public code and minimal visibility;
 * and services-have-tests, which asks for one test class per service, a convention projects that test by feature
 * don't follow.
 */
final class RecommendedRules {

    private RecommendedRules() {
    }

    static RuleSet of(CodeBase code) {
        GuardrailRules guardrails = new GuardrailRules(code);
        SuiteRules tests = new SuiteRules(code);
        DependencyRules dependencies = new DependencyRules(code);
        ApiRules api = new ApiRules(code);
        TypeRules types = new TypeRules(code);
        return new RuleSet(code, List.of(
                new RuleSet.Member("no-stubs", guardrails.noStubs()),
                new RuleSet.Member("no-suppressions", guardrails.noSuppressions()),
                new RuleSet.Member("no-widened-for-tests", guardrails.noWidenedForTests()),
                new RuleSet.Member("meaningful-tests", tests.testsAssertMeaningfully()),
                new RuleSet.Member("no-disabled-tests", tests.noDisabledTests()),
                new RuleSet.Member("tests-check-results", tests.testsCheckResults()),
                new RuleSet.Member("tests-use-their-subject", tests.testsUseTheirSubject()),
                new RuleSet.Member("tests-dont-mock-values", tests.testsDontMockValues()),
                new RuleSet.Member("tests-have-no-logic", tests.testsHaveNoLogic()),
                new RuleSet.Member("tests-fixed-clock", tests.testsUseAFixedClock()),
                new RuleSet.Member("tests-seeded-randomness", tests.testsUseSeededRandomness()),
                new RuleSet.Member("tests-no-environment", tests.testsDontReadTheEnvironment()),
                new RuleSet.Member("tests-no-sleep", tests.testsDontSleep()),
                new RuleSet.Member("class-cycles", new ArchitectureRules(code).classesHaveNoCycles()),
                new RuleSet.Member("no-queries-in-loops", dependencies.noQueriesInLoops()),
                new RuleSet.Member("constructors-do-no-work", dependencies.constructorsDoNoWork()),
                new RuleSet.Member("no-raw-threads", dependencies.noRawThreads()),
                new RuleSet.Member("accessors-change-nothing", api.accessorsChangeNothing()),
                new RuleSet.Member("no-flag-arguments", api.noFlagArguments()),
                new RuleSet.Member("no-stringly-typed-choices", api.noStringlyTypedChoices()),
                new RuleSet.Member("optional-usage", api.optionalUsage()),
                new RuleSet.Member("enums-over-constants", types.enumsOverConstants()),
                new RuleSet.Member("immutable-records", types.recordsAreImmutable()),
                new RuleSet.Member("focused-interfaces", types.interfacesAreFocused())), ClassFilter.ALL);
    }
}
