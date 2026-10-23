package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Layer;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.LayerCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Onion architecture: rings depend only inwards. Domain model, then domain services, then application services,
 * then adapters on the outside, which never depend on each other. Defaults: {@code ..domain.model..},
 * {@code ..domain.service..}, {@code ..application..}; adapters must be declared.
 */
public final class OnionArchitectureRule extends DesignRule<OnionArchitectureRule> {

    private static final String DOMAIN_MODEL = "Domain model";
    private static final String DOMAIN_SERVICE = "Domain service";
    private static final String APPLICATION_SERVICE = "Application service";

    private final List<Layer> rings;
    private final List<Layer> adapters;

    OnionArchitectureRule(CodeBase code, RuleScope scope, List<Layer> rings, List<Layer> adapters) {
        super(code, scope);
        this.rings = List.copyOf(rings);
        this.adapters = List.copyOf(adapters);
    }

    static List<Layer> defaultRings() {
        return List.of(new Layer(DOMAIN_MODEL, List.of("..domain.model..")),
                new Layer(DOMAIN_SERVICE, List.of("..domain.service..")),
                new Layer(APPLICATION_SERVICE, List.of("..application..")));
    }

    /**
     * Sets where the domain model lives.
     *
     * @param patterns ArchUnit package patterns
     * @return the reconfigured rule
     */
    public OnionArchitectureRule domainModels(String... patterns) {
        return withRing(0, patterns);
    }

    /**
     * Sets where the domain services live.
     *
     * @param patterns ArchUnit package patterns
     * @return the reconfigured rule
     */
    public OnionArchitectureRule domainServices(String... patterns) {
        return withRing(1, patterns);
    }

    /**
     * Sets where the application services live.
     *
     * @param patterns ArchUnit package patterns
     * @return the reconfigured rule
     */
    public OnionArchitectureRule applicationServices(String... patterns) {
        return withRing(2, patterns);
    }

    /**
     * Adds an adapter on the outer ring, such as {@code web} or {@code persistence}.
     *
     * @param name     the adapter's name, shown in violations
     * @param patterns ArchUnit package patterns of its classes
     * @return the reconfigured rule
     */
    public OnionArchitectureRule adapter(String name, String... patterns) {
        List<Layer> withAdapter = Stream.concat(adapters.stream(),
                Stream.of(new Layer("Adapter " + name, List.of(patterns)))).toList();
        return new OnionArchitectureRule(code(), scope(), rings, withAdapter);
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        Set<String> allRings = Set.of(DOMAIN_MODEL, DOMAIN_SERVICE, APPLICATION_SERVICE);
        Map<String, Set<String>> mayUse = Stream.concat(
                        Stream.of(Map.entry(DOMAIN_MODEL, Set.<String>of()),
                                Map.entry(DOMAIN_SERVICE, Set.of(DOMAIN_MODEL)),
                                Map.entry(APPLICATION_SERVICE, Set.of(DOMAIN_MODEL, DOMAIN_SERVICE))),
                        adapters.stream().map(adapter -> Map.entry(adapter.name(), allRings)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        List<Layer> layers = Stream.concat(rings.stream(), adapters.stream()).toList();
        return new LayerCheck("onion-architecture", layers, mayUse).check(code, filter);
    }

    @Override
    OnionArchitectureRule withScope(RuleScope scope) {
        return new OnionArchitectureRule(code(), scope, rings, adapters);
    }

    private OnionArchitectureRule withRing(int index, String... patterns) {
        List<Layer> changed = new ArrayList<>(rings);
        changed.set(index, new Layer(rings.get(index).name(), List.of(patterns)));
        return new OnionArchitectureRule(code(), scope(), changed, adapters);
    }
}
