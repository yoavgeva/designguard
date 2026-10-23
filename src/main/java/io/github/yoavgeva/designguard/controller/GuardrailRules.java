package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.MeaningfulTestsCheck;
import io.github.yoavgeva.designguard.service.MockedValuesCheck;
import io.github.yoavgeva.designguard.service.ServiceTestsCheck;
import io.github.yoavgeva.designguard.service.StubCheck;
import io.github.yoavgeva.designguard.service.SubjectUsageCheck;
import io.github.yoavgeva.designguard.service.UnusedPublicCodeCheck;
import java.util.Set;

/** Rules that catch the shortcuts AI agents take: unfinished code, silenced warnings, tests that prove nothing. */
public final class GuardrailRules {

    private final CodeBase code;

    GuardrailRules(CodeBase code) {
        this.code = code;
    }

    /**
     * Fails on unfinished code: {@code TODO}/{@code FIXME} comments, methods that only throw
     * {@code UnsupportedOperationException}, and empty method bodies without a comment saying why.
     *
     * @return the rule
     */
    public BasicRule noStubs() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new StubCheck().check(codeBase.sources(), filter));
    }

    /**
     * Fails on warnings silenced instead of fixed, in production and test code.
     *
     * @return the rule
     * @see NoSuppressionsRule
     */
    public NoSuppressionsRule noSuppressions() {
        return new NoSuppressionsRule(code, ClassFilter.ALL, Set.of());
    }

    /**
     * Fails on tests that prove nothing: no assertion at all, assertions on constants, or a value compared with
     * itself.
     *
     * @return the rule
     */
    public BasicRule testsAssertMeaningfully() {
        return new BasicRule(code, ClassFilter.ALL,
                (codeBase, filter) -> new MeaningfulTestsCheck().check(codeBase.testSources(), filter));
    }

    /**
     * Fails on disabled tests.
     *
     * @return the rule
     * @see NoDisabledTestsRule
     */
    public NoDisabledTestsRule noDisabledTests() {
        return new NoDisabledTestsRule(code, ClassFilter.ALL, false);
    }

    /**
     * Fails when a service-like class has no test class named {@code <Name>Test}, {@code <Name>Tests} or
     * {@code <Name>IT} in the test sources.
     *
     * @return the rule
     */
    public BasicRule serviceLikeClassesHaveTests() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new ServiceTestsCheck().check(codeBase, filter));
    }

    /**
     * Fails when a test class doesn't exercise the class it's named after: {@code OrderServiceTest} never uses
     * {@code OrderService}, or only mocks it.
     *
     * @return the rule
     */
    public BasicRule testsUseTheirSubject() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new SubjectUsageCheck().check(codeBase, filter));
    }

    /**
     * Fails when a test mocks a value: a record, an enum, another class that isn't service-like, or an interface whose
     * implementations all are values. Tests should create real values and mock only service-like types.
     *
     * @return the rule
     */
    public BasicRule testsDontMockValues() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new MockedValuesCheck().check(codeBase, filter));
    }

    /**
     * Fails on public types and methods nothing in the project uses. Meant for applications, where unused public
     * code is dead code; in a library it is the API.
     *
     * @return the rule
     */
    public BasicRule noUnusedPublicCode() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new UnusedPublicCodeCheck().check(codeBase, filter));
    }
}
