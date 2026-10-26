package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.resolution.MethodAmbiguityException;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Symbol resolution that never fails a check: when a symbol can't be resolved (missing classpath entry, unusual
 * syntax), the answer is "unknown" and the node is skipped.
 */
final class Resolution {

    private static final Set<String> OBJECT_METHODS = Set.of("toString", "equals", "hashCode", "clone", "finalize");

    private Resolution() {
    }

    /** The qualified name of the expression's static type, if it is a class, interface, enum or record. */
    static Optional<String> typeOf(Expression expression) {
        try {
            ResolvedType type = expression.calculateResolvedType();
            return type.isReferenceType() ? Optional.of(type.asReferenceType().getQualifiedName()) : Optional.empty();
        } catch (UnsolvedSymbolException | MethodAmbiguityException | UnsupportedOperationException | IllegalStateException
                | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The qualified name of the enum, if the expression names one of its constants, e.g. {@code Color.RED}. */
    static Optional<String> enumOfConstant(Expression expression) {
        Optional<String> name = constantLikeName(expression);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        try {
            ResolvedValueDeclaration value = expression instanceof NameExpr simple
                    ? simple.resolve()
                    : expression.asFieldAccessExpr().resolve();
            return value.isEnumConstant()
                    ? Optional.of(value.asEnumConstant().getType().describe())
                    : Optional.empty();
        } catch (UnsolvedSymbolException | MethodAmbiguityException | UnsupportedOperationException | IllegalStateException
                | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether the method overrides or implements a method of a supertype, with or without {@code @Override}: a
     * supertype declares a method of the same name and number of parameters. Supertypes are searched nearest first,
     * stopping at the first that declares one. False if a supertype can't be resolved.
     */
    static boolean overridesSupertype(MethodDeclaration method) {
        String name = method.getNameAsString();
        int parameters = method.getParameters().size();
        Optional<TypeDeclaration<?>> owner = Scopes.enclosingType(method);
        if (method.isPrivate() || method.isStatic() || owner.isEmpty()) {
            return false;
        }
        if (!hasSupertypes(owner.get())) {
            return OBJECT_METHODS.contains(name);
        }
        try {
            Deque<ResolvedReferenceType> pending = new ArrayDeque<>(owner.get().resolve().getAncestors(true));
            Set<String> seen = new HashSet<>();
            while (!pending.isEmpty()) {
                ResolvedReferenceType ancestor = pending.pop();
                if (!seen.add(ancestor.getQualifiedName())) {
                    continue;
                }
                if (ancestor.getDeclaredMethods().stream()
                        .anyMatch(inherited -> inherited.getName().equals(name) && inherited.getNoParams() == parameters)) {
                    return true;
                }
                ancestor.getTypeDeclaration().ifPresent(declaration -> pending.addAll(declaration.getAncestors(true)));
            }
            return false;
        } catch (UnsolvedSymbolException | MethodAmbiguityException | UnsupportedOperationException | IllegalStateException
                | IllegalArgumentException e) {
            return false;
        }
    }

    /** Whether the type names a supertype other than {@code Object}, as {@code extends} or {@code implements} do. */
    private static boolean hasSupertypes(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return !declaration.getExtendedTypes().isEmpty() || !declaration.getImplementedTypes().isEmpty();
        }
        return !(type instanceof NodeWithImplements<?> implementing) || !implementing.getImplementedTypes().isEmpty();
    }

    private static Optional<String> constantLikeName(Expression expression) {
        String name;
        if (expression instanceof NameExpr simple) {
            name = simple.getNameAsString();
        } else if (expression instanceof FieldAccessExpr access) {
            name = access.getNameAsString();
        } else {
            return Optional.empty();
        }
        return name.equals(name.toUpperCase(Locale.ROOT)) ? Optional.of(name) : Optional.empty();
    }
}
