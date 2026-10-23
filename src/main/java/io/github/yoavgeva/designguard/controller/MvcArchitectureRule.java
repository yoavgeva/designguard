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
 * Model-view-controller: controllers may use views, services and models; views and services may use models only;
 * models depend on no other layer. Defaults: {@code ..controller..}, {@code ..view..}, {@code ..service..},
 * {@code ..model..}.
 */
public final class MvcArchitectureRule extends DesignRule<MvcArchitectureRule> {

    private static final String CONTROLLER = "Controller";
    private static final String VIEW = "View";
    private static final String SERVICE = "Service";
    private static final String MODEL = "Model";

    private final Map<String, List<String>> packages;

    MvcArchitectureRule(CodeBase code, ClassFilter filter, Map<String, List<String>> packages) {
        super(code, filter);
        this.packages = Map.copyOf(packages);
    }

    static Map<String, List<String>> defaults() {
        return Map.of(CONTROLLER, List.of("..controller.."), VIEW, List.of("..view.."),
                SERVICE, List.of("..service.."), MODEL, List.of("..model.."));
    }

    /**
     * Sets where controllers live.
     *
     * @param patterns ArchUnit package patterns
     * @return the reconfigured rule
     */
    public MvcArchitectureRule controllers(String... patterns) {
        return with(CONTROLLER, patterns);
    }

    /**
     * Sets where views live.
     *
     * @param patterns ArchUnit package patterns
     * @return the reconfigured rule
     */
    public MvcArchitectureRule views(String... patterns) {
        return with(VIEW, patterns);
    }

    /**
     * Sets where services live.
     *
     * @param patterns ArchUnit package patterns
     * @return the reconfigured rule
     */
    public MvcArchitectureRule services(String... patterns) {
        return with(SERVICE, patterns);
    }

    /**
     * Sets where models live.
     *
     * @param patterns ArchUnit package patterns
     * @return the reconfigured rule
     */
    public MvcArchitectureRule models(String... patterns) {
        return with(MODEL, patterns);
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        List<Layer> layers = List.of(CONTROLLER, VIEW, SERVICE, MODEL).stream()
                .map(name -> new Layer(name, packages.get(name)))
                .toList();
        Map<String, Set<String>> mayUse = Map.of(CONTROLLER, Set.of(VIEW, SERVICE, MODEL),
                VIEW, Set.of(MODEL), SERVICE, Set.of(MODEL), MODEL, Set.of());
        return new LayerCheck("mvc-architecture", layers, mayUse).check(code, filter);
    }

    @Override
    MvcArchitectureRule withFilter(ClassFilter filter) {
        return new MvcArchitectureRule(code(), filter, packages);
    }

    private MvcArchitectureRule with(String layer, String... patterns) {
        Map<String, List<String>> changed = Stream.concat(
                        packages.entrySet().stream().filter(entry -> !entry.getKey().equals(layer)),
                        Stream.of(Map.entry(layer, List.of(patterns))))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new MvcArchitectureRule(code(), filter(), changed);
    }
}
