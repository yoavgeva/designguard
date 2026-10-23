package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Layer;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.LayerCheck;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Hexagonal architecture (ports and adapters): the domain depends on nothing else, the application on the domain,
 * and each adapter on the application and the domain, never on another adapter. Defaults: domain in
 * {@code ..domain..}, application in {@code ..application..}; adapters must be declared.
 */
public final class HexagonalArchitectureRule extends DesignRule<HexagonalArchitectureRule> {

    private static final String DOMAIN_LAYER = "Domain";
    private static final String APPLICATION_LAYER = "Application";

    private final List<String> domainPackages;
    private final List<String> applicationPackages;
    private final List<Layer> adapters;

    HexagonalArchitectureRule(CodeBase code, ClassFilter filter, List<String> domain, List<String> application,
                              List<Layer> adapters) {
        super(code, filter);
        this.domainPackages = List.copyOf(domain);
        this.applicationPackages = List.copyOf(application);
        this.adapters = List.copyOf(adapters);
    }

    /**
     * Sets where the domain lives.
     *
     * @param patterns ArchUnit package patterns
     * @return the reconfigured rule
     */
    public HexagonalArchitectureRule domain(String... patterns) {
        return new HexagonalArchitectureRule(code(), filter(), List.of(patterns), applicationPackages, adapters);
    }

    /**
     * Sets where the application (use cases, ports) lives.
     *
     * @param patterns ArchUnit package patterns
     * @return the reconfigured rule
     */
    public HexagonalArchitectureRule application(String... patterns) {
        return new HexagonalArchitectureRule(code(), filter(), domainPackages, List.of(patterns), adapters);
    }

    /**
     * Adds an adapter, such as {@code web} or {@code persistence}.
     *
     * @param name     the adapter's name, shown in violations
     * @param patterns ArchUnit package patterns of its classes
     * @return the reconfigured rule
     */
    public HexagonalArchitectureRule adapter(String name, String... patterns) {
        List<Layer> withAdapter = Stream.concat(adapters.stream(),
                Stream.of(new Layer("Adapter " + name, List.of(patterns)))).toList();
        return new HexagonalArchitectureRule(code(), filter(), domainPackages, applicationPackages, withAdapter);
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        List<Layer> layers = Stream.concat(
                Stream.of(new Layer(DOMAIN_LAYER, domainPackages), new Layer(APPLICATION_LAYER, applicationPackages)),
                adapters.stream()).toList();
        Map<String, Set<String>> mayUse = Stream.concat(
                        Stream.of(Map.entry(DOMAIN_LAYER, Set.<String>of()), Map.entry(APPLICATION_LAYER, Set.of(DOMAIN_LAYER))),
                        adapters.stream().map(adapter -> Map.entry(adapter.name(), Set.of(APPLICATION_LAYER, DOMAIN_LAYER))))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new LayerCheck("hexagonal-architecture", layers, mayUse).check(code, filter);
    }

    @Override
    HexagonalArchitectureRule withFilter(ClassFilter filter) {
        return new HexagonalArchitectureRule(code(), filter, domainPackages, applicationPackages, adapters);
    }
}
