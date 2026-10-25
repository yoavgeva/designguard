package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.PackageMatcher;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Layer;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks dependencies between architecture layers: a class may depend on its own layer and on the layers its layer
 * may use, nothing else. Classes outside every layer are not checked. Layered, MVC, hexagonal and onion
 * architectures are all this check with different layers and permissions.
 */
public final class LayerCheck {

    private final String rule;
    private final List<Layer> layers;
    private final Map<String, Set<String>> mayUse;

    /**
     * Configures the check.
     *
     * @param rule   the rule id shown in violations
     * @param layers the layers, in the order used in messages; a class belongs to the first layer it matches
     * @param mayUse for each layer name, the names of the other layers it may depend on
     */
    public LayerCheck(String rule, List<Layer> layers, Map<String, Set<String>> mayUse) {
        this.rule = rule;
        this.layers = List.copyOf(layers);
        this.mayUse = Map.copyOf(mayUse);
    }

    /**
     * Finds forbidden dependencies from the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        return classes.stream()
                .filter(origin -> classes.selected(filter, origin))
                .flatMap(origin -> layerOf(origin).stream().flatMap(layer -> violations(classes, origin, layer)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private Stream<Violation> violations(ProjectClasses classes, JavaClass origin, Layer originLayer) {
        Set<String> allowed = mayUse.getOrDefault(originLayer.name(), Set.of());
        Map<JavaClass, Integer> forbidden = classes.bytecode().dependenciesFrom(origin)
                .filter(dependency -> classes.isProject(dependency.target())
                        && !Hierarchy.isSelfOrNested(origin, dependency.target()))
                .filter(dependency -> layerOf(dependency.target())
                        .filter(target -> !target.name().equals(originLayer.name()) && !allowed.contains(target.name()))
                        .isPresent())
                .collect(Collectors.toMap(ClassDependency::target, ClassDependency::line, Math::min, LinkedHashMap::new));
        return forbidden.entrySet().stream().map(entry -> {
            Layer targetLayer = layerOf(entry.getKey()).orElseThrow();
            return classes.violation(rule, origin, entry.getValue(),
                    ProjectClasses.nameOf(origin) + " (layer " + originLayer.name() + ") depends on "
                            + ProjectClasses.nameOf(entry.getKey()) + " (layer " + targetLayer.name() + "), which "
                            + originLayer.name() + " may not use",
                    fix(originLayer, allowed));
        });
    }

    private String fix(Layer originLayer, Set<String> allowed) {
        if (allowed.isEmpty()) {
            return originLayer.name() + " may not depend on other layers; invert the dependency with an interface in "
                    + originLayer.name();
        }
        String usable = layers.stream().map(Layer::name).filter(allowed::contains).collect(Collectors.joining(", "));
        return "go through a layer " + originLayer.name() + " may use: " + usable;
    }

    private Optional<Layer> layerOf(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        return layers.stream()
                .filter(layer -> layer.packages().stream().anyMatch(pattern -> PackageMatcher.of(pattern).matches(packageName)))
                .findFirst();
    }
}
