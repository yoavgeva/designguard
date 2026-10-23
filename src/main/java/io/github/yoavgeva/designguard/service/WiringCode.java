package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.PackageMatcher;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Code whose job is to assemble the application, so it may create and hold concrete services: classes with a
 * {@code main} method, Spring {@code @Configuration} and {@code @SpringBootApplication} classes, Dagger
 * {@code @Module}s, and classes in the configured wiring packages. Nested classes of wiring code are wiring code.
 */
final class WiringCode {

    private static final Set<String> WIRING_ANNOTATIONS = Set.of("Configuration", "SpringBootApplication", "Module");

    private final List<String> patterns;

    WiringCode(List<String> patterns) {
        this.patterns = List.copyOf(patterns);
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
                || javaClass.getMethods().stream().anyMatch(WiringCode::isMain)
                || javaClass.getAnnotations().stream()
                        .anyMatch(annotation -> WIRING_ANNOTATIONS.contains(annotation.getRawType().getSimpleName()));
    }

    private static boolean isMain(JavaMethod method) {
        return "main".equals(method.getName())
                && method.getModifiers().contains(JavaModifier.STATIC)
                && method.getRawParameterTypes().size() == 1
                && method.getRawParameterTypes().getFirst().isEquivalentTo(String[].class);
    }
}
