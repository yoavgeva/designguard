package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.PackageMatcher;
import io.github.yoavgeva.designguard.model.JavaFeature;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Code whose job is to assemble the application, so it may create and hold concrete services: classes with a
 * {@code main} method (from Java 25 also an instance or parameterless one), Spring {@code @Configuration} and {@code @SpringBootApplication} classes, Dagger
 * {@code @Module}s, and classes in the configured wiring packages. Nested classes of wiring code are wiring code.
 */
final class WiringCode {

    private static final Set<String> WIRING_ANNOTATIONS = Set.of("Configuration", "SpringBootApplication", "Module");

    private final List<String> patterns;
    private final boolean instanceMainMethods;

    WiringCode(List<String> patterns, int javaRelease) {
        this.patterns = List.copyOf(patterns);
        this.instanceMainMethods = JavaFeature.INSTANCE_MAIN_METHODS.isAvailableIn(javaRelease);
    }

    boolean contains(JavaClass javaClass) {
        Optional<JavaClass> current = Optional.of(javaClass);
        while (current.isPresent()) {
            if (isWiring(current.get())) {
                return true;
            }
            current = current.get().getEnclosingClass();
        }
        return false;
    }

    private boolean isWiring(JavaClass javaClass) {
        return patterns.stream().anyMatch(pattern -> PackageMatcher.of(pattern).matches(javaClass.getPackageName()))
                || javaClass.getMethods().stream().anyMatch(this::isMain)
                || javaClass.getAnnotations().stream()
                        .anyMatch(annotation -> WIRING_ANNOTATIONS.contains(annotation.getRawType().getSimpleName()));
    }

    /** {@code static main(String[])}; from Java 25 also instance and parameterless ones, if not private. */
    private boolean isMain(JavaMethod method) {
        List<JavaClass> parameters = method.getRawParameterTypes();
        boolean takesArguments = parameters.size() == 1 && parameters.get(0).isEquivalentTo(String[].class);
        if (!"main".equals(method.getName())) {
            return false;
        }
        if (instanceMainMethods) {
            return !method.getModifiers().contains(JavaModifier.PRIVATE)
                    && method.getRawReturnType().isEquivalentTo(void.class)
                    && (takesArguments || parameters.isEmpty());
        }
        return method.getModifiers().contains(JavaModifier.STATIC) && takesArguments;
    }
}
