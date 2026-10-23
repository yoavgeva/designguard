package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.InterfacePolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds classes that hold or receive a concrete class where the policy wants an interface: through a field, a
 * constructor parameter or a method parameter. Wiring code is exempt.
 */
public final class InterfacePolicyCheck {

    private static final String RULE = "interface-policy";

    private final InterfacePolicy policy;
    private final Function<List<String>, Slices> slicing;

    /**
     * Configures the check. For {@link InterfacePolicy#PORTS}, a slice is the first package below a checked package.
     *
     * @param policy when an interface is required
     */
    public InterfacePolicyCheck(InterfacePolicy policy) {
        this(policy, Slices::belowEach);
    }

    /**
     * Configures the check with the slices whose boundaries {@link InterfacePolicy#PORTS} guards.
     *
     * @param policy       when an interface is required
     * @param slicePattern an ArchUnit pattern whose first capture group names the slice, e.g. {@code com.shop.(*)..}
     */
    public InterfacePolicyCheck(InterfacePolicy policy, String slicePattern) {
        this(policy, always(Slices.matching(Objects.requireNonNull(slicePattern, "slicePattern"))));
    }

    private InterfacePolicyCheck(InterfacePolicy policy, Function<List<String>, Slices> slicing) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.slicing = slicing;
    }

    private static Function<List<String>, Slices> always(Slices slices) {
        return checkedPackages -> slices;
    }

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        Context context = new Context(code.projectClasses(), code.serviceLike(),
                slicing.apply(code.settings().packages()));
        WiringCode wiring = code.wiring();
        return context.classes().stream()
                .filter(dependent -> context.classes().selected(filter, dependent) && !wiring.contains(dependent))
                .flatMap(dependent -> violations(context, filter, dependent))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private Stream<Violation> violations(Context context, ClassFilter filter, JavaClass dependent) {
        Map<JavaClass, List<Usage>> usagesByTarget = usages(dependent)
                .filter(usage -> context.classes().selected(filter, usage.origin()))
                .filter(usage -> context.classes().isProject(usage.target()))
                .filter(usage -> !Hierarchy.isSelfOrNested(dependent, usage.target()) && Hierarchy.isConcrete(usage.target()))
                .collect(Collectors.groupingBy(Usage::target, LinkedHashMap::new, Collectors.toList()));
        return usagesByTarget.entrySet().stream()
                .flatMap(entry -> violation(context, dependent, entry.getKey(), entry.getValue()).stream());
    }

    private Optional<Violation> violation(Context context, JavaClass dependent, JavaClass target, List<Usage> usages) {
        boolean serviceLike = context.serviceLike().contains(target);
        String dependentSlice = context.slices().sliceOf(dependent.getPackageName());
        String targetSlice = context.slices().sliceOf(target.getPackageName());
        boolean required = switch (policy) {
            case PORTS -> serviceLike && !dependentSlice.equals(targetSlice);
            case SERVICES -> serviceLike;
            case ALL -> serviceLike || ServiceLikeClasses.canBeServiceLike(target) && !target.isEnum();
        };
        if (!required) {
            return Optional.empty();
        }
        String members = usages.stream().map(Usage::member).distinct().collect(Collectors.joining(", "));
        String kind = serviceLike ? "concrete service-like " : "concrete ";
        String crossing = policy == InterfacePolicy.PORTS ? " across slices " + dependentSlice + " -> " + targetSlice : "";
        String targetName = ProjectClasses.nameOf(target);
        int line = usages.stream().mapToInt(Usage::line).filter(value -> value > 0).min().orElse(0);
        return Optional.of(context.classes().violation(RULE, dependent, line,
                ProjectClasses.nameOf(dependent) + " depends on " + kind + targetName + " (" + members + ")" + crossing,
                "depend on an interface instead of " + targetName + ", and let the wiring code pass it in"));
    }

    private static Stream<Usage> usages(JavaClass dependent) {
        Stream<Usage> fields = dependent.getFields().stream()
                .filter(field -> !field.getModifiers().contains(JavaModifier.SYNTHETIC))
                .flatMap(field -> field.getAllInvolvedRawTypes().stream()
                        .map(type -> new Usage(type, "field " + field.getName(), 0, field)));
        Stream<Usage> constructors = dependent.getConstructors().stream()
                .flatMap(constructor -> parameterUsages(constructor, "constructor"));
        Stream<Usage> methods = dependent.getMethods().stream()
                .filter(method -> !method.getModifiers().contains(JavaModifier.SYNTHETIC)
                        && !method.getModifiers().contains(JavaModifier.BRIDGE))
                .flatMap(method -> parameterUsages(method, "method " + method.getName()));
        return Stream.of(fields, constructors, methods).flatMap(stream -> stream);
    }

    private static Stream<Usage> parameterUsages(JavaCodeUnit codeUnit, String member) {
        int line = codeUnit.getSourceCodeLocation().getLineNumber();
        return codeUnit.getParameterTypes().stream()
                .flatMap(type -> type.getAllInvolvedRawTypes().stream())
                .map(type -> new Usage(type, member, line, codeUnit));
    }

    /** Everything the check needs about the project. */
    private record Context(ProjectClasses classes, ServiceLikeClasses serviceLike, Slices slices) {
    }

    /** One way a class uses another: through which member, at which line. */
    private record Usage(JavaClass target, String member, int line, JavaMember origin) {
    }
}
