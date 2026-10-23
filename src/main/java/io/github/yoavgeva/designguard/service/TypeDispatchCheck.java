package io.github.yoavgeva.designguard.service;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.JavaFeature;
import io.github.yoavgeva.designguard.model.TypeDispatchPolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds code that branches on the kind of a project type (its subtype or its enum constant) in ways that break
 * when a new kind is added: open hierarchies, {@code default} branches that hide new cases, {@code instanceof}
 * chains, and types branched on in too many places the compiler can't check.
 */
public final class TypeDispatchCheck {

    private static final String RULE = "type-dispatch";

    private final TypeDispatchPolicy policy;
    private final int maxPlacesPerType;
    private final int javaRelease;

    /**
     * Configures the check.
     *
     * @param policy           whether branching on subtypes is allowed only over sealed types
     * @param maxPlacesPerType how many unchecked branches on one type are allowed
     * @param javaRelease      the Java version the code targets, so fixes only suggest what it has
     */
    public TypeDispatchCheck(TypeDispatchPolicy policy, int maxPlacesPerType, int javaRelease) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.maxPlacesPerType = maxPlacesPerType;
        this.javaRelease = javaRelease;
    }

    /**
     * Finds violations in the sources the filter selects.
     *
     * @param sources the parsed sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        List<DispatchSite> sites = new DispatchSites(sources).find().stream()
                .filter(site -> Scopes.selected(filter, site.file(), site.node()))
                .toList();
        return Stream.concat(sites.stream().flatMap(site -> siteViolation(site).stream()), placesPerType(sites))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private Optional<Violation> siteViolation(DispatchSite site) {
        return defaultBranch(site).or(() -> openDispatch(site));
    }

    private static Optional<Violation> defaultBranch(DispatchSite site) {
        DeclaredType type = site.type();
        if (!site.mustBeExhaustive() || !site.hasDefault() || !type.isSealed() && !type.isEnum()) {
            return Optional.empty();
        }
        String what = type.isEnum() ? "enum " + type.simpleName() : "sealed " + type.simpleName();
        String added = type.isEnum() ? "constant" : "subtype";
        return violation(site, "switch over " + what + " has a default branch, so the compiler won't flag a new "
                + added, "remove the default branch and handle every " + added);
    }

    private Optional<Violation> openDispatch(DispatchSite site) {
        if (site.insideOwnType() || site.type().isEnum() || policy != TypeDispatchPolicy.SEALED_ONLY) {
            return Optional.empty();
        }
        String name = site.type().simpleName();
        String construct = site.kind() == DispatchSite.Kind.TYPE_SWITCH ? "switch" : "instanceof chain";
        boolean canSwitchOnTypes = JavaFeature.SWITCH_ON_TYPES.isAvailableIn(javaRelease);
        if (!site.type().isSealed()) {
            String seal = canSwitchOnTypes ? "make " + name + " sealed and switch over it" : "make " + name + " sealed";
            return violation(site, construct + " on the subtypes of " + name
                            + ", which isn't sealed, so a new subtype won't be caught",
                    seal + ", or move the varying behaviour into " + name);
        }
        if (site.kind() == DispatchSite.Kind.INSTANCEOF_CHAIN && canSwitchOnTypes) {
            return violation(site, "instanceof chain on sealed " + name
                            + ": the compiler can't check that it covers every subtype",
                    "use a switch over " + name + " without a default branch");
        }
        return Optional.empty();
    }

    private Stream<Violation> placesPerType(List<DispatchSite> sites) {
        Map<DeclaredType, List<DispatchSite>> uncheckedByType = sites.stream()
                .filter(site -> !site.checkedByCompiler() && !site.insideOwnType())
                .collect(Collectors.groupingBy(DispatchSite::type));
        return uncheckedByType.entrySet().stream()
                .filter(entry -> entry.getValue().size() > maxPlacesPerType)
                .map(entry -> tooManyPlaces(entry.getKey(), entry.getValue()));
    }

    private Violation tooManyPlaces(DeclaredType type, List<DispatchSite> sites) {
        String places = sites.stream().map(DispatchSite::location).sorted().collect(Collectors.joining(", "));
        JavaFeature exhaustiveSwitch = type.isEnum() ? JavaFeature.SWITCH_EXPRESSIONS : JavaFeature.SWITCH_ON_TYPES;
        String orSwitch = exhaustiveSwitch.isAvailableIn(javaRelease)
                ? ", or switch over it exhaustively without a default branch" : "";
        return Scopes.violation(RULE, type.file(), type.declaration().getName(),
                type.simpleName() + " is branched on in " + sites.size() + " places the compiler can't check (max "
                        + maxPlacesPerType + "): " + places,
                "move the varying behaviour into " + type.simpleName() + orSwitch);
    }

    private static Optional<Violation> violation(DispatchSite site, String message, String fix) {
        return Optional.of(Scopes.violation(RULE, site.file(), site.node(), message, fix));
    }
}
