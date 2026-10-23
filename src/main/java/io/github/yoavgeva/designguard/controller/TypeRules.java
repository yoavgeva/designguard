package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.InheritancePolicy;
import io.github.yoavgeva.designguard.model.JavaFeature;
import io.github.yoavgeva.designguard.model.TypeDispatchPolicy;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.DataClassCheck;
import io.github.yoavgeva.designguard.service.EnumLikeConstantsCheck;
import io.github.yoavgeva.designguard.service.FatInterfaceCheck;
import io.github.yoavgeva.designguard.service.RawIdentifiersCheck;
import io.github.yoavgeva.designguard.service.RecordImmutabilityCheck;
import io.github.yoavgeva.designguard.service.SealedHierarchyCheck;
import io.github.yoavgeva.designguard.service.StatelessServicesCheck;
import io.github.yoavgeva.designguard.service.ValueImmutabilityCheck;
import java.util.List;

/** Rules about how types are modelled: hierarchies, records, immutability and branching on kinds. */
public final class TypeRules {

    private final CodeBase code;

    TypeRules(CodeBase code) {
        this.code = code;
    }

    /**
     * Fails on fragile branching on the kind of a type.
     *
     * @param policy whether branching on subtypes is allowed only over sealed types
     * @return the rule
     * @see TypeDispatchRule
     */
    public TypeDispatchRule typeDispatch(TypeDispatchPolicy policy) {
        return new TypeDispatchRule(code, RuleScope.DEFAULT, policy);
    }

    /**
     * Fails on record components callers can still change: collections and maps not copied with
     * {@code List.copyOf}-style calls in the constructor, arrays, and mutable JDK types like {@code Date}.
     *
     * @return the rule
     */
    public BasicRule recordsAreImmutable() {
        return new BasicRule(code, RuleScope.DEFAULT,
                (codeBase, filter) -> new RecordImmutabilityCheck().check(codeBase.sources(), filter));
    }

    /**
     * Fails on classes that only carry data (private final fields, accessors, {@code equals}, {@code hashCode},
     * {@code toString}) and should be records. Needs code that targets Java 16 or later.
     *
     * @return the rule
     */
    public BasicRule dataClassesAreRecords() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> {
            codeBase.requires(JavaFeature.RECORDS, "dataClassesAreRecords()");
            return new DataClassCheck().check(codeBase.sources(), filter);
        });
    }

    /**
     * Fails when a value (a class that isn't service-like) has a non-final instance field. Enums, records,
     * exceptions, wiring code and persistence entities are skipped.
     *
     * @return the rule
     */
    public BasicRule valueTypesAreImmutable() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new ValueImmutabilityCheck().check(codeBase, filter));
    }

    /**
     * Fails when a service-like class holds changing state: a non-final instance field, or a final one holding a
     * mutable collection, atomic or string builder. Services are shared, so their state leaks between callers.
     *
     * @return the rule
     */
    public BasicRule servicesAreStateless() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new StatelessServicesCheck().check(codeBase, filter));
    }

    /**
     * Fails on identifiers typed as raw strings, numbers or UUIDs in the given packages: fields, record components and
     * non-private parameters named {@code id} or {@code somethingId}. A type per identifier stops a customer ID from
     * being passed where an order ID belongs.
     *
     * @param domainPackages ArchUnit package patterns to check, e.g. {@code ..domain..}
     * @return the rule
     */
    public BasicRule typedIdentifiers(String... domainPackages) {
        return new BasicRule(code, RuleScope.DEFAULT,
                (codeBase, filter) -> new RawIdentifiersCheck(List.of(domainPackages)).check(codeBase.sources(), filter));
    }

    /**
     * Fails on interfaces wider than any of their clients needs: at least four methods, at least two clients, and no
     * client calling more than half of them. Split them into role interfaces (the interface segregation principle).
     *
     * @return the rule
     */
    public BasicRule interfacesAreFocused() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new FatInterfaceCheck().check(codeBase, filter));
    }

    /**
     * Fails on groups of int, long or String constants that enumerate cases, like {@code STATUS_OPEN = 0} and
     * {@code STATUS_PAID = 1}: an enum gives them a type the compiler checks.
     *
     * @return the rule
     */
    public BasicRule enumsOverConstants() {
        return new BasicRule(code, RuleScope.DEFAULT,
                (codeBase, filter) -> new EnumLikeConstantsCheck().check(codeBase.sources(), filter));
    }

    /**
     * Fails when an interface or abstract class is implemented only inside the project but isn't sealed.
     * Functional interfaces are skipped. Meant for applications: a library's users may implement its interfaces.
     * Needs code that targets Java 17 or later.
     *
     * @return the rule
     */
    public BasicRule closedHierarchiesAreSealed() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> {
            codeBase.requires(JavaFeature.SEALED_TYPES, "closedHierarchiesAreSealed()");
            return new SealedHierarchyCheck().check(codeBase, filter);
        });
    }

    /**
     * Checks class inheritance per the policy; add {@link InheritanceRule#maxDepth(int)} to limit depth.
     *
     * @param policy how much inheritance is allowed
     * @return the rule
     */
    public InheritanceRule inheritance(InheritancePolicy policy) {
        return new InheritanceRule(code, RuleScope.DEFAULT, policy, Integer.MAX_VALUE);
    }
}
