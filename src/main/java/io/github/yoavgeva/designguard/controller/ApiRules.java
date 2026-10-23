package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.service.AccessorSideEffectsCheck;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.CheckedExceptionCheck;
import io.github.yoavgeva.designguard.service.EffectfulStaticsCheck;
import io.github.yoavgeva.designguard.service.FlagArgumentCheck;
import io.github.yoavgeva.designguard.service.MinimalVisibilityCheck;
import io.github.yoavgeva.designguard.service.NullMarkedCheck;
import io.github.yoavgeva.designguard.service.OptionalUsageCheck;
import io.github.yoavgeva.designguard.service.UntypedApiCheck;

/** Rules about the shape of methods and types other code calls: parameters, nullness, exceptions, visibility. */
public final class ApiRules {

    private final CodeBase code;

    ApiRules(CodeBase code) {
        this.code = code;
    }

    /**
     * Fails on flag arguments: a non-private method with a boolean parameter used directly as an {@code if} or
     * ternary condition. Such a method does two things, and callers' {@code true}/{@code false} says which.
     *
     * @return the rule
     */
    public BasicRule noFlagArguments() {
        return new BasicRule(code, ClassFilter.ALL,
                (codeBase, filter) -> new FlagArgumentCheck().check(codeBase.sources(), filter));
    }

    /**
     * Fails on packages without JSpecify's {@code @NullMarked} on their {@code package-info.java} or the module.
     *
     * @return the rule
     */
    public BasicRule packagesAreNullMarked() {
        return new BasicRule(code, ClassFilter.ALL,
                (codeBase, filter) -> new NullMarkedCheck().check(codeBase.sources(), filter));
    }

    /**
     * Fails on {@code Optional} used as a field or a parameter, and on methods returning {@code null} where their
     * result is an {@code Optional}.
     *
     * @return the rule
     */
    public BasicRule optionalUsage() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new OptionalUsageCheck().check(codeBase, filter));
    }

    /**
     * Fails on checked exception classes and on methods declaring checked exceptions, except where the signature
     * is inherited.
     *
     * @return the rule
     */
    public BasicRule noCheckedExceptions() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new CheckedExceptionCheck().check(codeBase, filter));
    }

    /**
     * Fails on static methods outside wiring code that touch the outside world, such as I/O, the clock or the
     * environment, when other classes call them: those callers can't replace the effect in tests. Callers that
     * implement a project interface or abstract class are exempt, since their own callers can replace them.
     *
     * @return the rule
     */
    public BasicRule noEffectfulStaticMethods() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new EffectfulStaticsCheck().check(codeBase, filter));
    }

    /**
     * Fails on public methods that take or return untyped data: {@code Object}, a map to {@code Object} such as
     * {@code Map<String, Object>}, or a JSON tree. A record names the fields and their types.
     *
     * @return the rule
     */
    public BasicRule typedApis() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new UntypedApiCheck().check(codeBase, filter));
    }

    /**
     * Fails on {@code get}, {@code is} and {@code has} methods that assign a field of their class: callers read
     * accessors as queries. Lazy initialization and {@code getAnd...} methods are fine.
     *
     * @return the rule
     */
    public BasicRule accessorsChangeNothing() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new AccessorSideEffectsCheck().check(codeBase, filter));
    }

    /**
     * Fails on public top-level types only their own package uses; they should be package-private.
     *
     * @return the rule
     */
    public BasicRule minimalVisibility() {
        return new BasicRule(code, ClassFilter.ALL, (codeBase, filter) -> new MinimalVisibilityCheck().check(codeBase, filter));
    }
}
