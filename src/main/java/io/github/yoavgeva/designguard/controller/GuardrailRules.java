package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.ServiceTestsCheck;
import io.github.yoavgeva.designguard.service.StubCheck;
import io.github.yoavgeva.designguard.service.UnusedPublicCodeCheck;
import io.github.yoavgeva.designguard.service.WidenedForTestsCheck;
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
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new StubCheck().check(codeBase.sources(), filter));
    }

    /**
     * Fails on warnings silenced instead of fixed, in production and test code.
     *
     * @return the rule
     * @see NoSuppressionsRule
     */
    public NoSuppressionsRule noSuppressions() {
        return new NoSuppressionsRule(code, RuleScope.DEFAULT, Set.of());
    }

    /**
     * Fails when a service-like class has no test class named {@code <Name>Test}, {@code <Name>Tests} or
     * {@code <Name>IT} in the test sources.
     *
     * @return the rule
     */
    public BasicRule serviceLikeClassesHaveTests() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new ServiceTestsCheck().check(codeBase, filter));
    }

    /**
     * Fails on package-private or protected methods and constructors that no production code calls but tests in
     * their package do: they were widened so tests could reach inside the class.
     *
     * @return the rule
     */
    public BasicRule noWidenedForTests() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new WidenedForTestsCheck().check(codeBase, filter));
    }

    /**
     * Fails on public types and methods nothing in the project uses. Meant for applications, where unused public
     * code is dead code; in a library it is the API.
     *
     * @return the rule
     */
    public BasicRule noUnusedPublicCode() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new UnusedPublicCodeCheck().check(codeBase, filter));
    }
}
