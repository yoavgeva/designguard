package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
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
}
