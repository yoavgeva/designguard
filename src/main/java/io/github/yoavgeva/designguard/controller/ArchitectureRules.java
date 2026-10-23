package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.service.BoundaryCheck;
import io.github.yoavgeva.designguard.service.ClassCycleCheck;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.FeatureSliceCheck;
import java.util.List;

/**
 * Presets for the architectures Java teams use most. Each checks dependencies between groups of packages and
 * reports every forbidden one with the layers involved. Classes outside every declared layer are not checked.
 */
public final class ArchitectureRules {

    private final CodeBase code;

    ArchitectureRules(CodeBase code) {
        this.code = code;
    }

    /**
     * A layered architecture; declare the layers top to bottom with {@link LayeredArchitectureRule#layer}.
     *
     * @return the rule, with no layers yet
     */
    public LayeredArchitectureRule layered() {
        return new LayeredArchitectureRule(code, ClassFilter.ALL, List.of(), false);
    }

    /**
     * Model-view-controller, with packages {@code ..controller..}, {@code ..view..}, {@code ..service..} and
     * {@code ..model..} unless reconfigured.
     *
     * @return the rule
     */
    public MvcArchitectureRule mvc() {
        return new MvcArchitectureRule(code, ClassFilter.ALL, MvcArchitectureRule.defaults());
    }

    /**
     * Hexagonal architecture (ports and adapters); declare adapters with {@link HexagonalArchitectureRule#adapter}.
     *
     * @return the rule
     */
    public HexagonalArchitectureRule hexagonal() {
        return new HexagonalArchitectureRule(code, ClassFilter.ALL, List.of("..domain.."), List.of("..application.."),
                List.of());
    }

    /**
     * Onion architecture; declare adapters with {@link OnionArchitectureRule#adapter}.
     *
     * @return the rule
     */
    public OnionArchitectureRule onion() {
        return new OnionArchitectureRule(code, ClassFilter.ALL, OnionArchitectureRule.defaultRings(), List.of());
    }

    /**
     * Package by feature: slices use each other's API package only, and have no cycles.
     *
     * @return the rule
     */
    public FeatureSlicesRule featureSlices() {
        return new FeatureSlicesRule(code, ClassFilter.ALL, new FeatureSliceCheck());
    }

    /**
     * Fails on classes that depend on each other, directly or around a loop. Nested classes count as their top-level
     * class, and a type naming its own subtypes, as a sealed type's {@code permits} clause does, isn't a cycle.
     *
     * @return the rule
     */
    public BasicRule classesHaveNoCycles() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new ClassCycleCheck().check(codeBase, filter));
    }

    /**
     * Keeps frameworks out of the domain, as hexagonal, onion and clean architectures require: Spring, Jakarta EE and
     * javax (persistence, servlets, REST, CDI, injection, validation, JSON binding), Hibernate, Jackson, Gson, Guice,
     * Dagger, Micronaut, Quarkus, MicroProfile, Vert.x and Dropwizard, annotations included.
     *
     * @param domainPackages ArchUnit package patterns of the domain, e.g. {@code ..domain..}
     * @return the rule
     */
    public BasicRule frameworksStayOutOf(String... domainPackages) {
        return new BasicRule(code, ClassFilter.ALL, BoundaryCheck.frameworks(List.of(domainPackages))::check);
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
