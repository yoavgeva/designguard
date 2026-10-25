package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.PackageMatcher;
import java.util.List;

/** Cuts the project into slices, such as features or layers. */
sealed interface Slices {

    /**
     * A slice is the first package below a root package: {@code com.shop.orders} for {@code com.shop}. Packages
     * outside every root, and the roots themselves, belong to the unnamed slice {@code ""}.
     */
    static Slices belowEach(List<String> rootPackages) {
        return new BelowRoots(rootPackages);
    }

    /**
     * A slice is the first capture group of a pattern, e.g. {@code com.shop.(*)..}. A package the pattern doesn't
     * match is a slice of its own.
     */
    static Slices matching(String pattern) {
        return new ByPattern(PackageMatcher.of(pattern));
    }

    /**
     * Names the slice a package belongs to.
     *
     * @param packageName a package name
     * @return the slice's name
     */
    String sliceOf(String packageName);

    /** Slices directly below root packages. */
    final class BelowRoots implements Slices {

        private final List<String> rootPackages;

        BelowRoots(List<String> rootPackages) {
            this.rootPackages = List.copyOf(rootPackages);
        }

        @Override
        public String sliceOf(String packageName) {
            return rootPackages.stream()
                    .filter(root -> packageName.startsWith(root + "."))
                    .map(root -> packageName.substring(root.length() + 1).split("\\.")[0])
                    .findFirst()
                    .orElse("");
        }
    }

    /** Slices named by a pattern's capture group. */
    final class ByPattern implements Slices {

        private final PackageMatcher pattern;

        ByPattern(PackageMatcher pattern) {
            this.pattern = pattern;
        }

        @Override
        public String sliceOf(String packageName) {
            return pattern.match(packageName)
                    .filter(result -> result.getNumberOfGroups() > 0)
                    .map(result -> result.getGroup(1))
                    .orElse(packageName);
        }
    }
}
