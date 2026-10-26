package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.internal.CodeBase;
import io.github.yoavgeva.designguard.internal.InterfacePolicyCheck;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.InterfacePolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;

/**
 * Fails when a class holds or receives a concrete class where an interface is required: through a field, a
 * constructor parameter or a method parameter. Which dependencies need an interface depends on the
 * {@link InterfacePolicy}. Wiring code is exempt.
 */
public final class InterfacePolicyRule extends DesignRule<InterfacePolicyRule> {

    private final InterfacePolicy policy;
    private final InterfacePolicyCheck check;

    InterfacePolicyRule(CodeBase code, RuleScope scope, InterfacePolicy policy, InterfacePolicyCheck check) {
        super(code, scope);
        this.policy = policy;
        this.check = check;
    }

    /**
     * Defines the slices whose boundaries {@link InterfacePolicy#PORTS} guards. By default a slice is the first
     * package below a checked package.
     *
     * @param pattern an ArchUnit package pattern whose first capture group names the slice, e.g.
     *                {@code com.shop.(*)..}
     * @return the reconfigured rule
     */
    public InterfacePolicyRule slicedBy(String pattern) {
        return new InterfacePolicyRule(code(), scope(), policy, new InterfacePolicyCheck(policy, pattern));
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        return check.check(code, filter);
    }

    @Override
    InterfacePolicyRule withScope(RuleScope scope) {
        return new InterfacePolicyRule(code(), scope, policy, check);
    }
}
