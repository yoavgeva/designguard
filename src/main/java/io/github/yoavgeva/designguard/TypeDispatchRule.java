package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.internal.CodeBase;
import io.github.yoavgeva.designguard.internal.TypeDispatchCheck;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.JavaFeature;
import io.github.yoavgeva.designguard.model.TypeDispatchPolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;

/**
 * Fails on code that branches on the kind of a project type (a {@code switch} on its subtypes, an
 * {@code instanceof} chain, or comparisons against enum constants) in ways that break when a new kind is added.
 *
 * <ul>
 *   <li>With {@link TypeDispatchPolicy#SEALED_ONLY}, branching on subtypes is allowed only over sealed types, through
 *       a switch the compiler checks from Java 21. It needs code that targets Java 17 or later.</li>
 *   <li>A {@code default} branch in a switch over a sealed type or enum is reported: it hides new cases.</li>
 *   <li>A type branched on in more places than {@link #maxPlacesPerType(int)} allows, counting only branches the
 *       compiler doesn't check, is reported once at its declaration.</li>
 * </ul>
 */
public final class TypeDispatchRule extends DesignRule<TypeDispatchRule> {

    private static final int DEFAULT_MAX_PLACES_PER_TYPE = 3;

    private final TypeDispatchPolicy policy;
    private final int maxPlaces;

    TypeDispatchRule(CodeBase code, RuleScope scope, TypeDispatchPolicy policy) {
        this(code, scope, policy, DEFAULT_MAX_PLACES_PER_TYPE);
    }

    private TypeDispatchRule(CodeBase code, RuleScope scope, TypeDispatchPolicy policy, int maxPlaces) {
        super(code, scope);
        this.policy = policy;
        this.maxPlaces = maxPlaces;
    }

    /**
     * Sets how many unchecked branches on one type are allowed. The default is 3.
     *
     * @param max the limit, at least 0
     * @return the reconfigured rule
     * @throws IllegalArgumentException if {@code max} is negative
     */
    public TypeDispatchRule maxPlacesPerType(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("maxPlacesPerType must be >= 0, was " + max);
        }
        return new TypeDispatchRule(code(), scope(), policy, max);
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        if (policy == TypeDispatchPolicy.SEALED_ONLY) {
            code.requires(JavaFeature.SEALED_TYPES, "typeDispatch(SEALED_ONLY)");
        }
        return new TypeDispatchCheck(policy, maxPlaces, code.javaRelease()).check(code.sources(), filter);
    }

    @Override
    TypeDispatchRule withScope(RuleScope scope) {
        return new TypeDispatchRule(code(), scope, policy, maxPlaces);
    }
}
