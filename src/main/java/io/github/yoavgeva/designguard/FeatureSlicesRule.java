package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.internal.CodeBase;
import io.github.yoavgeva.designguard.internal.FeatureSliceCheck;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;

/**
 * Package by feature: each package directly below a checked package is a feature slice. Its top-level package is
 * its API and its subpackages are its internals. Slices may use each other's API only, and must not form cycles.
 */
public final class FeatureSlicesRule extends DesignRule<FeatureSlicesRule> {

    private final FeatureSliceCheck check;

    FeatureSlicesRule(CodeBase code, RuleScope scope, FeatureSliceCheck check) {
        super(code, scope);
        this.check = check;
    }

    /**
     * Takes the slices from below another package than the checked ones, e.g. {@code com.shop.features}.
     *
     * @param packageName the package directly above the slices
     * @return the reconfigured rule
     */
    public FeatureSlicesRule slicesBelow(String packageName) {
        return new FeatureSlicesRule(code(), scope(), new FeatureSliceCheck(packageName));
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        return check.check(code, filter);
    }

    @Override
    FeatureSlicesRule withScope(RuleScope scope) {
        return new FeatureSlicesRule(code(), scope, check);
    }
}
