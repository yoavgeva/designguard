package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.service.BranchingTestsCheck;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.MeaningfulTestsCheck;
import io.github.yoavgeva.designguard.service.MockedValuesCheck;
import io.github.yoavgeva.designguard.service.NondeterministicTestsCheck;
import io.github.yoavgeva.designguard.service.QueryVerificationCheck;
import io.github.yoavgeva.designguard.service.ServiceTestsCheck;
import io.github.yoavgeva.designguard.service.SubjectUsageCheck;

/** Rules about the test suite: tests assert something, use real values and their subject, and take one path. */
public final class SuiteRules {

    private final CodeBase code;

    SuiteRules(CodeBase code) {
        this.code = code;
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
     * Fails when a test verifies a query, {@code verify(mock).method()} for a method that returns a value: stub
     * queries and assert on the result, and verify only commands.
     *
     * @return the rule
     */
    public BasicRule testsCheckResults() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new QueryVerificationCheck().check(codeBase, filter));
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
     * Fails on tests with logic: an {@code if}, a {@code switch} or a loop in a test method. Split the cases into
     * tests, or make them parameters of a {@code @ParameterizedTest}.
     *
     * @return the rule
     */
    public BasicRule testsHaveNoLogic() {
        return new BasicRule(code, ClassFilter.ALL,
                (codeBase, filter) -> new BranchingTestsCheck().check(codeBase.testSources(), filter));
    }

    /**
     * Fails on test code that gives different results on different runs or machines: it reads the system clock, uses
     * unseeded randomness, reads environment variables or system properties, or sleeps.
     *
     * @return the rule
     */
    public BasicRule testsAreDeterministic() {
        return new BasicRule(code, ClassFilter.ALL,
                (codeBase, filter) -> new NondeterministicTestsCheck().check(codeBase.testSources(), filter));
    }
}
