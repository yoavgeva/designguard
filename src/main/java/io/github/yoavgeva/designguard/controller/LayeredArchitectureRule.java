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
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A layered architecture: layers declared top to bottom, each may use the layers below it. With {@link #strict()},
 * each layer may use only the layer directly below it.
 */
public final class LayeredArchitectureRule extends DesignRule<LayeredArchitectureRule> {

    private final List<Layer> layers;
    private final boolean strictLayering;

    LayeredArchitectureRule(CodeBase code, ClassFilter filter, List<Layer> layers, boolean strict) {
        super(code, filter);
        this.layers = List.copyOf(layers);
        this.strictLayering = strict;
    }

    /**
     * Adds the next layer down.
     *
     * @param name     the layer's name, shown in violations
     * @param packages ArchUnit package patterns of its classes
     * @return the rule with the layer added
     */
    public LayeredArchitectureRule layer(String name, String... packages) {
        List<Layer> withLayer = Stream.concat(layers.stream(), Stream.of(new Layer(name, List.of(packages)))).toList();
        return new LayeredArchitectureRule(code(), filter(), withLayer, strictLayering);
    }

    /**
     * Lets each layer use only the layer directly below it, not any lower one.
     *
     * @return the stricter rule
     */
    public LayeredArchitectureRule strict() {
        return new LayeredArchitectureRule(code(), filter(), layers, true);
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        Map<String, Set<String>> mayUse = IntStream.range(0, layers.size()).boxed()
                .collect(Collectors.toMap(index -> layers.get(index).name(), this::layersBelow));
        return new LayerCheck("layered-architecture", layers, mayUse).check(code, filter);
    }

    private Set<String> layersBelow(int index) {
        int end = strictLayering ? Math.min(index + 2, layers.size()) : layers.size();
        return layers.subList(index + 1, end).stream().map(Layer::name).collect(Collectors.toSet());
    }

    @Override
    LayeredArchitectureRule withFilter(ClassFilter filter) {
        return new LayeredArchitectureRule(code(), filter, layers, strictLayering);
    }
}
