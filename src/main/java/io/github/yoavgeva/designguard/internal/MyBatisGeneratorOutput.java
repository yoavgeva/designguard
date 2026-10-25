package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The classes MyBatis Generator writes into the source roots, which are generated code even though their sources are
 * there: each {@code ...Example} class with its nested {@code GeneratedCriteria}, the model it is named after, and the
 * mapper interfaces whose {@code countByExample} and {@code selectByExample} take such an example. Regenerating them
 * overwrites any change by hand.
 */
final class MyBatisGeneratorOutput {

    private static final String CRITERIA = "GeneratedCriteria";
    private static final String EXAMPLE = "Example";
    private static final Set<String> BY_EXAMPLE = Set.of("countByExample", "selectByExample", "deleteByExample",
            "updateByExample", "updateByExampleSelective");
    /** A mapper has at least two of the generator's by-example methods. */
    private static final int MIN_BY_EXAMPLE = 2;

    private final Set<String> generated;

    /**
     * Finds the generator's output among the classes.
     *
     * @param classes the project's classes
     */
    MyBatisGeneratorOutput(List<JavaClass> classes) {
        Set<String> examples = classes.stream()
                .filter(type -> CRITERIA.equals(type.getSimpleName()))
                .flatMap(criteria -> criteria.getEnclosingClass().stream())
                .filter(example -> example.getSimpleName().endsWith(EXAMPLE))
                .map(JavaClass::getName)
                .collect(Collectors.toSet());
        Set<String> names = new HashSet<>(examples);
        examples.forEach(example -> names.add(example.substring(0, example.length() - EXAMPLE.length())));
        classes.stream()
                .filter(type -> type.isInterface() && byExampleMethods(type, examples) >= MIN_BY_EXAMPLE)
                .map(JavaClass::getName)
                .forEach(names::add);
        this.generated = Set.copyOf(names);
    }

    /** Whether the generator wrote the class, or the class it is nested in. */
    boolean contains(JavaClass type) {
        String name = type.getName();
        int nested = name.indexOf('$');
        return generated.contains(nested < 0 ? name : name.substring(0, nested));
    }

    private static long byExampleMethods(JavaClass mapper, Set<String> examples) {
        return mapper.getMethods().stream()
                .filter(method -> BY_EXAMPLE.contains(method.getName()) && takesAnExample(method, examples))
                .map(JavaMethod::getName)
                .distinct()
                .count();
    }

    private static boolean takesAnExample(JavaMethod method, Set<String> examples) {
        return method.getRawParameterTypes().stream().anyMatch(parameter -> examples.contains(parameter.getName()));
    }
}
