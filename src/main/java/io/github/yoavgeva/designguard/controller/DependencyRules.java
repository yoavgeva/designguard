package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.InterfacePolicy;
import io.github.yoavgeva.designguard.service.BoundaryCheck;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.FunctionalCoreChecks;
import io.github.yoavgeva.designguard.service.InjectServicesCheck;
import io.github.yoavgeva.designguard.service.InterfacePolicyCheck;
import io.github.yoavgeva.designguard.service.StableDependenciesCheck;
import java.util.List;

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
        return new InterfacePolicyRule(code, ClassFilter.ALL, policy, new InterfacePolicyCheck(policy));
    }

    /**
     * Fails when a service-like class is created with {@code new}, or fetched through a static singleton accessor,
     * outside wiring code. Services should be injected.
     *
     * @return the rule
     */
    public BasicRule injectServices() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new InjectServicesCheck().check(codeBase, filter));
    }

    /**
     * Fails when code outside wiring reads the system clock ({@code LocalDate.now()},
     * {@code System.currentTimeMillis()}, {@code new Date()}...) instead of an injected {@code java.time.Clock}.
     *
     * @return the rule
     */
    public BasicRule clockIsInjected() {
        return new BasicRule(code, ClassFilter.ALL, FunctionalCoreChecks.clock()::check);
    }

    /**
     * Fails when code outside wiring creates randomness ({@code new Random()}, {@code UUID.randomUUID()}...) instead
     * of using an injected generator.
     *
     * @return the rule
     */
    public BasicRule randomnessIsInjected() {
        return new BasicRule(code, ClassFilter.ALL, FunctionalCoreChecks.randomness()::check);
    }

    /**
     * Fails when code outside wiring reads environment variables or system properties instead of injected values.
     *
     * @return the rule
     */
    public BasicRule configurationIsInjected() {
        return new BasicRule(code, ClassFilter.ALL, FunctionalCoreChecks.configuration()::check);
    }

    /**
     * Fails when code outside wiring starts threads by hand instead of submitting work to an executor.
     *
     * @return the rule
     */
    public BasicRule noRawThreads() {
        return new BasicRule(code, ClassFilter.ALL,
                (codeBase, filter) -> FunctionalCoreChecks.rawThreads(codeBase.javaRelease()).check(codeBase, filter));
    }

    /**
     * Fails when a slice depends on a less stable slice (the Stable Dependencies Principle). A slice is the first
     * package below a checked package.
     *
     * @return the rule
     */
    public BasicRule stableDependencies() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new StableDependenciesCheck().check(codeBase, filter));
    }

    /**
     * Fails when classes in the domain use infrastructure exceptions: JDBC, I/O, JPA, Spring data access, Jackson,
     * Kafka, AWS, or the packages given to {@link DesignGuard#treatingAsImpure(String...)}.
     *
     * @param domainPackages ArchUnit package patterns of the domain, e.g. {@code ..domain..}
     * @return the rule
     */
    public BasicRule infrastructureExceptionsStayOutOf(String... domainPackages) {
        return new BasicRule(code, ClassFilter.ALL, BoundaryCheck.infrastructureExceptions(List.of(domainPackages))::check);
    }

    /**
     * Fails when classes in the given packages use persistence entities ({@code @Entity}, {@code @Embeddable},
     * {@code @MappedSuperclass}) instead of DTOs or domain types.
     *
     * @param apiPackages ArchUnit package patterns, e.g. {@code ..web..}
     * @return the rule
     */
    public BasicRule entitiesStayOutOf(String... apiPackages) {
        return new BasicRule(code, ClassFilter.ALL, BoundaryCheck.entities(List.of(apiPackages))::check);
    }
}
