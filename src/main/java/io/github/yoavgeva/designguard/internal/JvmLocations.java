package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.Optional;
import java.util.Set;

/**
 * System properties that say where the JVM, its classes and the working directory are, and what separates lines and
 * paths: a test reading {@code line.separator} to expect the platform's line ends, or {@code java.home} to start a
 * child JVM, runs the same on every machine. {@code user.home}, {@code os.name} and the like vary what a test does.
 */
final class JvmLocations {

    private static final Set<String> KEYS = Set.of("line.separator", "file.separator", "path.separator", "java.home",
            "java.class.path", "java.io.tmpdir", "user.dir", "surefire.test.class.path");

    private JvmLocations() {
    }

    /**
     * Whether the reading is {@code System.getProperty(key)} with one of these keys, written in place or held in a
     * local variable, as {@code String key = "user.dir"} does.
     */
    static boolean isRead(Node reading) {
        return reading instanceof MethodCallExpr call && "getProperty".equals(call.getNameAsString())
                && !call.getArguments().isEmpty() && keyOf(call.getArgument(0)).filter(KEYS::contains).isPresent();
    }

    private static Optional<String> keyOf(Expression argument) {
        if (argument instanceof StringLiteralExpr literal) {
            return Optional.of(literal.asString());
        }
        if (argument instanceof NameExpr name) {
            return Scopes.enclosingCallable(name).stream()
                    .flatMap(callable -> callable.findAll(VariableDeclarator.class).stream())
                    .filter(variable -> variable.getNameAsString().equals(name.getNameAsString()))
                    .findFirst()
                    .flatMap(VariableDeclarator::getInitializer)
                    .filter(StringLiteralExpr.class::isInstance)
                    .map(value -> ((StringLiteralExpr) value).asString());
        }
        return Optional.empty();
    }
}
