package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.PackageMatcher;
import java.util.List;
import java.util.Optional;

/**
 * Cuts the project into slices, such as features or layers. By default a slice is the first package below a checked
 * package ({@code com.shop.orders} for {@code com.shop}); a pattern with a capture group overrides that, e.g.
 * {@code com.shop.(*)..}.
 */
final class Slices {

    private final List<String> rootPackages;
    private final Optional<PackageMatcher> pattern;

    Slices(List<String> rootPackages, Optional<String> pattern) {
        this.rootPackages = List.copyOf(rootPackages);
        this.pattern = pattern.map(PackageMatcher::of);
    }

    String sliceOf(String packageName) {
        return pattern.map(matcher -> matcher.match(packageName)
                        .filter(result -> result.getNumberOfGroups() > 0)
                        .map(result -> result.getGroup(1))
                        .orElse(packageName))
                .orElseGet(() -> defaultSlice(packageName));
    }

    private String defaultSlice(String packageName) {
        return rootPackages.stream()
                .filter(root -> packageName.startsWith(root + "."))
                .map(root -> packageName.substring(root.length() + 1).split("\\.")[0])
                .findFirst()
                .orElse("");
    }
}
