package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Conditions that only pick a value, so the boolean they test is data rather than a choice of behaviour:
 * {@code bool ? 1 : 0}, or an {@code if} and {@code else} that differ only in constants.
 */
final class ValueChoices {

    private ValueChoices() {
    }

    /** {@code if (value) { out.append("true"); } else { out.append("false"); }}: both branches differ only in constants. */
    static boolean picksAValue(IfStmt statement) {
        return statement.getElseStmt()
                .filter(otherwise -> shape(single(statement.getThenStmt())).equals(shape(single(otherwise))))
                .isPresent();
    }

    private static Statement single(Statement statement) {
        return statement.isBlockStmt() && statement.asBlockStmt().getStatements().size() == 1
                ? statement.asBlockStmt().getStatement(0) : statement;
    }

    /** The statement's nodes in order, each by kind and name, with every constant the same. */
    private static List<String> shape(Statement statement) {
        List<String> nodes = new ArrayList<>();
        statement.walk(Node.TreeTraversal.PREORDER, node -> nodes.add(node instanceof LiteralExpr ? "constant"
                : node instanceof SimpleName name ? name.getIdentifier() : node.getClass().getSimpleName()));
        return nodes;
    }

    /**
     * Whether the choice only picks a value: between constants, as {@code out.write(bool ? "true" : "false")} does, or,
     * when the method returns it, between variables or fields too, as a converter like
     * {@code return bool ? yes : no;} does. Passing a picked mode on, as {@code load(eager ? EAGER : LAZY)} does, still
     * chooses a behaviour.
     */
    static boolean isValue(ConditionalExpr choice) {
        boolean returned = choice.getParentNode().filter(ReturnStmt.class::isInstance).isPresent();
        return isValue(choice.getThenExpr(), returned) && isValue(choice.getElseExpr(), returned);
    }

    private static boolean isValue(Expression expression, boolean returned) {
        Expression value = unwrap(expression);
        if (value instanceof ConditionalExpr choice) {
            return isValue(choice.getThenExpr(), returned) && isValue(choice.getElseExpr(), returned);
        }
        if (value instanceof FieldAccessExpr field) {
            return returned && (field.getScope().isThisExpr() || isValue(field.getScope(), true));
        }
        return value instanceof LiteralExpr || returned && value instanceof NameExpr
                || value instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS
                        && isValue(unary.getExpression(), returned);
    }

    /** The expression inside any parentheses. */
    static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current instanceof EnclosedExpr enclosed) {
            current = enclosed.getInner();
        }
        return current;
    }
}
