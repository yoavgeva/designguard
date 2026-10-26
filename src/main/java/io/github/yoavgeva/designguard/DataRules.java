package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.internal.CodeBase;
import io.github.yoavgeva.designguard.internal.FloatingPointMoneyCheck;
import io.github.yoavgeva.designguard.internal.MassAssignmentCheck;
import io.github.yoavgeva.designguard.internal.UnboundedQueriesCheck;

/**
 * Rules about stored data: who may set which fields, what concurrent writes do to them, what type money is stored as,
 * and how many rows a request loads.
 */
public final class DataRules {

    private final CodeBase code;

    DataRules(CodeBase code) {
        this.code = code;
    }

    /**
     * Fails on request handlers that bind the request to a type with a privileged property: one that grants access, as
     * {@code role}, {@code admin} and {@code authorities} do, names an owner, as {@code userId}, {@code ownerId} and
     * {@code tenantId} do, or records a check the server makes, as {@code verified}, {@code confirmedAt} and
     * {@code approved} do. Whoever sends the request can set it on their own account. A parameter binds the request
     * when it's {@code @RequestBody}, {@code @ModelAttribute}, {@code @BeanParam}, or a project type without an
     * annotation naming another source; a property binds when a public setter, a public field or a record component sets
     * it, and isn't {@code @JsonIgnore}d. A handler that method security or a permission check guards passes, as
     * {@link GuardrailRules#writesAreAuthorized()} reads one: an admin may set roles.
     *
     * @return the rule
     */
    public BasicRule noMassAssignment() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new MassAssignmentCheck().check(codeBase, filter));
    }

    /**
     * Fails on money stored as a floating-point number: a {@code float}, {@code double}, {@code Float} or
     * {@code Double} field or record component whose name names an amount of money, as {@code totalPrice},
     * {@code shippingFee} and {@code balance} do. A float can't hold most decimal amounts exactly: {@code 0.1 + 0.2} is
     * {@code 0.30000000000000004}. Names that make it a ratio or count, as {@code taxRate} and {@code feePercent} do,
     * pass, and so do constants.
     *
     * @return the rule
     */
    public BasicRule noFloatingPointMoney() {
        return new BasicRule(code, RuleScope.DEFAULT,
                (codeBase, filter) -> new FloatingPointMoneyCheck().check(codeBase.sources(), filter));
    }

    /**
     * Fails on request handlers that load every row of a table: a Spring MVC or JAX-RS handler that calls, directly or
     * through project code, a Spring Data repository's {@code findAll()} or {@code findAll(Sort)}, or a MyBatis-Plus
     * service's {@code list()}. Each request loads more as the table grows, until it times out. Paginate it, or filter
     * it to what the response shows. A table that stays small, such as a list of countries, can stay whole: leave its
     * handler out with {@code excluding} or {@code ignoring}.
     *
     * @return the rule
     */
    public BasicRule noUnboundedQueries() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new UnboundedQueriesCheck().check(codeBase, filter));
    }
}
