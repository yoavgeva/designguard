package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.MethodAmbiguityException;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.Locale;
import java.util.Optional;

/**
 * Symbol resolution that never fails a check: when a symbol can't be resolved (missing classpath entry, unusual
 * syntax), the answer is "unknown" and the node is skipped.
 */
final class Resolution {

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
