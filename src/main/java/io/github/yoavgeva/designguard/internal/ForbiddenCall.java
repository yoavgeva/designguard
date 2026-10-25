package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A call, constructor call or method reference that a rule forbids outside wiring code.
 *
 * @param matches whether an access target is this forbidden call
 * @param fix     the fix to suggest, given the forbidden target
 */
record ForbiddenCall(Predicate<CodeUnitAccessTarget> matches, Function<CodeUnitAccessTarget, String> fix) {

    static final String CONSTRUCTOR = "<init>";

    static ForbiddenCall of(String owner, Set<String> methods, String fix) {
        return new ForbiddenCall(target -> owner.equals(target.getOwner().getName()) && methods.contains(target.getName()),
                target -> fix);
    }

    static ForbiddenCall withoutParameters(String owner, String method, String fix) {
        return new ForbiddenCall(target -> owner.equals(target.getOwner().getName())
                && method.equals(target.getName())
                && target.getRawParameterTypes().isEmpty(), target -> fix);
    }

    /** How the call reads in a message: {@code Type.method()} or {@code new Type()}. */
    static String describe(CodeUnitAccessTarget target) {
        String owner = ProjectClasses.nameOf(target.getOwner());
        return CONSTRUCTOR.equals(target.getName()) ? "new " + owner + "()" : owner + "." + target.getName() + "()";
    }
}
