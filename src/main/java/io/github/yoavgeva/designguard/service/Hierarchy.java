package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Facts about classes and how they relate. */
final class Hierarchy {

    /** JDK interfaces of collections, maps and iterators, whose methods read and change data in memory. */
    private static final List<String> COLLECTIONS = List.of("java.lang.Iterable", "java.util.Iterator", "java.util.Map",
            "java.util.Map$Entry", "java.util.Enumeration", "java.util.Spliterator");

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

    /**
     * Whether a supertype from outside the project declares the method, so its name, and what callers expect of it,
     * come from there, as with JDBC's {@code Statement.getResultSet()} or {@code ExecutorService.submit(Runnable)}.
     */
    static boolean isDeclaredOutside(JavaClass type, JavaMethod method, Predicate<JavaClass> isProject) {
        return Stream.concat(type.getAllRawSuperclasses().stream(), type.getAllRawInterfaces().stream())
                .filter(supertype -> !isProject.test(supertype))
                .flatMap(supertype -> supertype.getMethods().stream())
                .anyMatch(declared -> declared.getName().equals(method.getName())
                        && declared.getRawParameterTypes().equals(method.getRawParameterTypes()));
    }

    /**
     * Whether the call names a method of a JDK collection, map or iterator interface, as {@code iterator().next()} or
     * {@code get(key)} on a project collection do: its callers use the receiver as data in memory, whatever other
     * classes implementing the interface do.
     */
    static boolean isCollectionMethod(CodeUnitAccessTarget target) {
        return target.getOwner().getAllRawInterfaces().stream()
                .filter(type -> (type.getName().startsWith("java.util.") || COLLECTIONS.contains(type.getName()))
                        && COLLECTIONS.stream().anyMatch(type::isAssignableTo))
                .flatMap(type -> type.getMethods().stream())
                .anyMatch(method -> method.getName().equals(target.getName())
                        && method.getRawParameterTypes().equals(target.getRawParameterTypes()));
    }

    private static boolean declaredAbove(JavaClass type, JavaCodeUnit unit) {
        return Stream.concat(type.getAllRawSuperclasses().stream(), type.getAllRawInterfaces().stream())
                .flatMap(parent -> parent.getMethods().stream())
                .anyMatch(method -> method.getName().equals(unit.getName())
                        && method.getRawParameterTypes().equals(unit.getRawParameterTypes()));
    }

    /**
     * Whether the type is a project interface annotated {@code @FunctionalInterface}: like the interfaces of
     * {@code java.util.function}, it passes behaviour as data, so what one implementation does says nothing about the
     * code that calls or holds it.
     */
    static boolean isFunctionalInterface(JavaClass type) {
        return type.isInterface() && type.isAnnotatedWith(FunctionalInterface.class);
    }

    /** Whether the class can be instantiated: not an interface and not abstract. */
    static boolean isConcrete(JavaClass javaClass) {
        return !javaClass.isInterface() && !javaClass.getModifiers().contains(JavaModifier.ABSTRACT);
    }
}
