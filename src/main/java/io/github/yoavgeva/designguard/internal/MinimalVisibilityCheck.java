package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds public top-level types that only their own package uses: they should be package-private, so the package's
 * API shows what other packages may rely on. Unused types and wiring code are skipped.
 */
public final class MinimalVisibilityCheck {

    private static final String RULE = "minimal-visibility";

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        WiringCode wiring = code.wiring();
        return classes.stream()
                .filter(type -> type.isTopLevelClass() && type.getModifiers().contains(JavaModifier.PUBLIC))
                .filter(type -> classes.selected(filter, type) && !wiring.contains(type))
                .filter(type -> usedOnlyInItsPackage(classes, type))
                .map(type -> classes.violation(RULE, type, 0,
                        ProjectClasses.nameOf(type) + " is public, but only package " + type.getPackageName() + " uses it",
                        "make it package-private"))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean usedOnlyInItsPackage(ProjectClasses classes, JavaClass type) {
        Set<String> userPackages = classes.bytecode().usersOf(type)
                .filter(user -> !Hierarchy.isSelfOrNested(type, user))
                .map(JavaClass::getPackageName)
                .collect(Collectors.toSet());
        return userPackages.equals(Set.of(type.getPackageName()));
    }
}
