package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.FeatureSliceCheck;
import java.util.List;

/**
 * Package by feature: each package directly below a checked package is a feature slice. Its top-level package is
 * its API and its subpackages are its internals. Slices may use each other's API only, and must not form cycles.
 */
public final class FeatureSlicesRule extends DesignRule<FeatureSlicesRule> {

    private final FeatureSliceCheck check;

    FeatureSlicesRule(CodeBase code, ClassFilter filter, FeatureSliceCheck check) {
        super(code, filter);
        this.check = check;
    }

    /**
     * Takes the slices from below another package than the checked ones, e.g. {@code com.shop.features}.
     *
     * @param packageName the package directly above the slices
     * @return the reconfigured rule
     */
    public FeatureSlicesRule slicesBelow(String packageName) {
        return new FeatureSlicesRule(code(), filter(), new FeatureSliceCheck(packageName));
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        return check.check(code, filter);
    }

    @Override
    FeatureSlicesRule withFilter(ClassFilter filter) {
        return new FeatureSlicesRule(code(), filter, check);
    }
}
