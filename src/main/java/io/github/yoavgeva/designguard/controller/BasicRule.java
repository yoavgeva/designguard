package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.CodeBase;
import java.util.List;
import java.util.function.BiFunction;

/** A rule with no settings beyond which classes it applies to. What it checks is documented where it is created. */
public final class BasicRule extends DesignRule<BasicRule> {

    private final BiFunction<CodeBase, ClassFilter, List<Violation>> check;

    BasicRule(CodeBase code, RuleScope scope, BiFunction<CodeBase, ClassFilter, List<Violation>> check) {
        super(code, scope);
        this.check = check;
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        return check.apply(code, filter);
    }

    @Override
    BasicRule withScope(RuleScope scope) {
        return new BasicRule(code(), scope, check);
    }
}
