package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.internal.CodeBase;
import io.github.yoavgeva.designguard.internal.ConstructorWorkCheck;
import io.github.yoavgeva.designguard.internal.FunctionalCoreChecks;
import io.github.yoavgeva.designguard.internal.InjectServicesCheck;
import io.github.yoavgeva.designguard.internal.InterfacePolicyCheck;
import io.github.yoavgeva.designguard.internal.QueriesInLoopsCheck;
import io.github.yoavgeva.designguard.internal.StableDependenciesCheck;
import io.github.yoavgeva.designguard.internal.TransactionIoCheck;
import io.github.yoavgeva.designguard.model.InterfacePolicy;

/**
 * Rules about how classes depend on each other. They read compiled classes, and share one classification of
 * service-like classes: classes that do I/O, read the clock, randomness or the environment, or depend on such a class.
 */
public final class DependencyRules {

    private final CodeBase code;

    DependencyRules(CodeBase code) {
        this.code = code;
    }

    /**
     * Fails when a concrete class is used where the policy requires an interface.
     *
     * @param policy when an interface is required
     * @return the rule
     */
    public InterfacePolicyRule interfacePolicy(InterfacePolicy policy) {
        return new InterfacePolicyRule(code, RuleScope.DEFAULT, policy, new InterfacePolicyCheck(policy));
    }

    /**
     * Fails when a service-like class is created with {@code new}, or fetched through a static singleton accessor,
     * outside wiring code. Services should be injected.
     *
     * @return the rule
     */
    public BasicRule injectServices() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new InjectServicesCheck().check(codeBase, filter));
    }

    /**
     * Fails when code outside wiring reads the system clock ({@code LocalDate.now()},
     * {@code System.currentTimeMillis()}, {@code new Date()}...) instead of an injected {@code java.time.Clock}.
     *
     * @return the rule
     */
    public BasicRule clockIsInjected() {
        return new BasicRule(code, RuleScope.DEFAULT, FunctionalCoreChecks.clock()::check);
    }

    /**
     * Fails when code outside wiring creates randomness ({@code new Random()}, {@code UUID.randomUUID()}...) instead
     * of using an injected generator.
     *
     * @return the rule
     */
    public BasicRule randomnessIsInjected() {
        return new BasicRule(code, RuleScope.DEFAULT, FunctionalCoreChecks.randomness()::check);
    }

    /**
     * Fails when code outside wiring reads environment variables or system properties instead of injected values.
     *
     * @return the rule
     */
    public BasicRule configurationIsInjected() {
        return new BasicRule(code, RuleScope.DEFAULT, FunctionalCoreChecks.configuration()::check);
    }

    /**
     * Fails when code outside wiring starts threads by hand instead of submitting work to an executor.
     *
     * @return the rule
     */
    public BasicRule noRawThreads() {
        return new BasicRule(code, RuleScope.DEFAULT,
                (codeBase, filter) -> FunctionalCoreChecks.rawThreads(codeBase.javaRelease()).check(codeBase, filter));
    }

    /**
     * Fails when a constructor or static initializer outside wiring code does work: calls I/O, the clock, an injected
     * collaborator or other effectful code, instead of only storing what it is given.
     *
     * @return the rule
     */
    public BasicRule constructorsDoNoWork() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new ConstructorWorkCheck().check(codeBase, filter));
    }

    /**
     * Fails on calls inside a transaction that leave the database: HTTP, messaging, mail or the network, made by a
     * {@code @Transactional} method directly or through project code it calls. A rollback can't take them back, and
     * the transaction holds its database connection while they wait.
     *
     * @return the rule
     */
    public BasicRule noIoInTransactions() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new TransactionIoCheck().check(codeBase, filter));
    }

    /**
     * Fails on queries made once per item, the N+1 pattern: a collaborator's method that touches the outside world
     * and returns a value, called inside a loop or from a lambda handed to a stream or {@code forEach}.
     *
     * @return the rule
     */
    public BasicRule noQueriesInLoops() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new QueriesInLoopsCheck().check(codeBase, filter));
    }

    /**
     * Fails when a slice depends on a less stable slice (the Stable Dependencies Principle). A slice is the first
     * package below a checked package.
     *
     * @return the rule
     */
    public BasicRule stableDependencies() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new StableDependenciesCheck().check(codeBase, filter));
    }
}
