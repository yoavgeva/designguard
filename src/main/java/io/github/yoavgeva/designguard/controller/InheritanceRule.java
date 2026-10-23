package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.InheritancePolicy;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.InheritanceCheck;
import java.util.List;

/**
 * Checks class inheritance per an {@link InheritancePolicy}, and optionally how deep hierarchies may get.
 * Exceptions and framework-proxied classes ({@code @Service}, {@code @Entity}...) are skipped.
 */
public final class InheritanceRule extends DesignRule<InheritanceRule> {

    private final InheritancePolicy policy;
    private final int depthLimit;

    InheritanceRule(CodeBase code, RuleScope scope, InheritancePolicy policy, int depthLimit) {
        super(code, scope);
        this.policy = policy;
        this.depthLimit = depthLimit;
    }

    /**
     * Limits how many superclasses (other than {@code Object}) a class may have.
     *
     * @param depth the limit, at least 0
     * @return the reconfigured rule
     * @throws IllegalArgumentException if {@code depth} is negative
     */
    public InheritanceRule maxDepth(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("maxDepth must be >= 0, was " + depth);
        }
        return new InheritanceRule(code(), scope(), policy, depth);
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        return new InheritanceCheck(policy, depthLimit).check(code, filter);
    }

    @Override
    InheritanceRule withScope(RuleScope scope) {
        return new InheritanceRule(code(), scope, policy, depthLimit);
    }
}
