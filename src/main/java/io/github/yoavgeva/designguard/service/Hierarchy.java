package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.stream.Stream;

/** Facts about classes and how they relate. */
final class Hierarchy {

    private Hierarchy() {
    }

    /** Whether the two are the same class, or one is nested inside the other. */
    static boolean isSelfOrNested(JavaClass first, JavaClass second) {
        return first.equals(second)
                || second.getName().startsWith(first.getName() + "$")
                || first.getName().startsWith(second.getName() + "$");
    }

    /**
     * Whether a supertype declares a method with this name and these parameters, so the signature is inherited. An
     * override of a generic method, such as {@code write(JsonWriter, Number)} for {@code write(JsonWriter, T)}, has
     * other parameter types than the method it overrides, so the compiler adds a bridge method with the supertype's
     * erased signature that calls it, and the bridge says the signature is inherited.
     */
    static boolean isInherited(JavaClass type, JavaCodeUnit unit) {
        if (unit instanceof JavaConstructor) {
            return false;
        }
        return declaredAbove(type, unit) || type.getMethods().stream()
                .filter(method -> method.getModifiers().contains(JavaModifier.BRIDGE)
                        && method.getCallsFromSelf().stream().anyMatch(call -> call.getTarget().getName().equals(unit.getName())
                                && call.getTarget().getRawParameterTypes().equals(unit.getRawParameterTypes())))
                .anyMatch(bridge -> declaredAbove(type, bridge));
    }

    private static boolean declaredAbove(JavaClass type, JavaCodeUnit unit) {
        return Stream.concat(type.getAllRawSuperclasses().stream(), type.getAllRawInterfaces().stream())
                .flatMap(parent -> parent.getMethods().stream())
                .anyMatch(method -> method.getName().equals(unit.getName())
                        && method.getRawParameterTypes().equals(unit.getRawParameterTypes()));
    }

    /** Whether the class can be instantiated: not an interface and not abstract. */
    static boolean isConcrete(JavaClass javaClass) {
        return !javaClass.isInterface() && !javaClass.getModifiers().contains(JavaModifier.ABSTRACT);
    }
}
