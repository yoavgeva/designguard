package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * System properties a test saves to restore: {@code String home = System.getProperty("user.home")}, whose every use
 * puts the value back with {@code System.setProperty(...)} or checks it for null, as a test that sets the property
 * itself does in a {@code finally} block or an {@code @AfterEach} method. The machine's value never reaches the code
 * under test.
 */
final class SavedProperties {

    private SavedProperties() {
    }

    /** Whether the reading is stored in a variable or field whose every use restores it. */
    static boolean isSaved(Node reading) {
        return reading instanceof MethodCallExpr call && "getProperty".equals(call.getNameAsString())
                && storedIn(reading).filter(variable -> {
                    List<Node> uses = usesOf(reading, variable);
                    return !uses.isEmpty() && uses.stream().allMatch(SavedProperties::restores);
                }).isPresent();
    }

    /** The variable or field the reading initializes or is assigned to. */
    private static Optional<String> storedIn(Node reading) {
        Optional<Node> parent = reading.getParentNode();
        if (parent.filter(VariableDeclarator.class::isInstance).isPresent()) {
            return Optional.of(((VariableDeclarator) parent.get()).getNameAsString());
        }
        return parent.filter(node -> node instanceof AssignExpr assign && assign.getValue().equals(reading)
                        && (assign.getTarget().isNameExpr() || assign.getTarget().isFieldAccessExpr()))
                .map(node -> ((NodeWithSimpleName<?>) ((AssignExpr) node).getTarget()).getNameAsString());
    }

    /** The reads of the variable in the class around the reading, assignments to it aside. */
    private static List<Node> usesOf(Node reading, String variable) {
        return Scopes.enclosingType(reading).stream()
                .flatMap(type -> Stream.<Node>concat(type.findAll(NameExpr.class).stream(),
                        type.findAll(FieldAccessExpr.class).stream()))
                .filter(use -> ((NodeWithSimpleName<?>) use).getNameAsString().equals(variable)
                        && use.getParentNode().filter(parent -> parent instanceof AssignExpr assign
                                && assign.getTarget().equals(use)).isEmpty())
                .toList();
    }

    /** Whether the use puts the value back, as {@code System.setProperty(key, saved)}, or checks it for null. */
    private static boolean restores(Node use) {
        return use.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr call && "setProperty".equals(call.getNameAsString())
                        && call.getArguments().size() == 2 && call.getArgument(1).equals(use)
                        || parent instanceof BinaryExpr comparison && isNullCheck(comparison))
                .isPresent();
    }

    private static boolean isNullCheck(BinaryExpr comparison) {
        boolean equality = comparison.getOperator() == BinaryExpr.Operator.EQUALS
                || comparison.getOperator() == BinaryExpr.Operator.NOT_EQUALS;
        return equality && Stream.of(comparison.getLeft(), comparison.getRight()).anyMatch(Expression::isNullLiteralExpr);
    }
}
