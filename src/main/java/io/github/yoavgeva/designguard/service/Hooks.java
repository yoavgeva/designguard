package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.regex.Pattern;

/**
 * Methods whose body may do nothing, or only refuse, on purpose: hooks that a subclass or a caller's implementation
 * replaces, and methods whose Javadoc says what they do.
 */
final class Hooks {

    private Hooks() {
    }

    /**
     * Whether the callable is a hook: it overrides or implements a supertype's method, with or without
     * {@code @Override}, is an interface's default method, or is a method of an abstract class that subclasses may
     * override.
     */
    static boolean isHook(CallableDeclaration<?> callable) {
        if (!(callable instanceof MethodDeclaration method)) {
            return false;
        }
        boolean overridable = !method.isPrivate() && !method.isStatic() && !method.isFinal();
        boolean inAbstractClass = Scopes.enclosingType(method)
                .filter(type -> type instanceof ClassOrInterfaceDeclaration declaration && !declaration.isInterface()
                        && declaration.hasModifier(Modifier.Keyword.ABSTRACT))
                .isPresent();
        return Scopes.hasAnnotation(method, "Override") || method.isDefault() || overridable && inAbstractClass
                || Resolution.overridesSupertype(method);
    }

    /** Whether the callable's Javadoc has a match for the pattern. */
    static boolean javadocSays(CallableDeclaration<?> callable, Pattern words) {
        return callable.getJavadocComment().filter(javadoc -> words.matcher(javadoc.getContent()).find()).isPresent();
    }
}
