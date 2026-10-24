package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Clock readings a test overwrites before using them: {@code Calendar day = Calendar.getInstance();} followed by
 * {@code day.clear()}, {@code day.setTime(...)} or {@code day.setTimeInMillis(...)}, or {@code new Date()} followed
 * by {@code setTime(...)}. The value the clock gave is gone before anything reads it.
 */
final class OverwrittenTimes {

    private static final Set<String> RESETS = Set.of("clear", "setTime", "setTimeInMillis");

    private OverwrittenTimes() {
    }

    /** Whether the reading is stored in a variable whose next use, in the same block, resets its whole value. */
    static boolean isOverwritten(Node reading) {
        return storedIn(reading).filter(variable -> nextUse(reading, variable)
                        .filter(use -> use.isExpressionStmt() && use.asExpressionStmt().getExpression() instanceof MethodCallExpr call
                                && RESETS.contains(call.getNameAsString())
                                && call.getScope().filter(scope -> isName(scope, variable)).isPresent())
                        .isPresent())
                .isPresent();
    }

    /** The local variable the reading is assigned to or initializes, if it is one. */
    private static Optional<String> storedIn(Node reading) {
        Optional<Node> parent = reading.getParentNode();
        if (parent.filter(VariableDeclarator.class::isInstance).isPresent()) {
            return Optional.of(((VariableDeclarator) parent.get()).getNameAsString());
        }
        return parent.filter(node -> node instanceof AssignExpr assign && assign.getValue().equals(reading)
                        && assign.getTarget().isNameExpr())
                .map(node -> ((AssignExpr) node).getTarget().asNameExpr().getNameAsString());
    }

    /** The first statement after the reading's, in its block, that names the variable. */
    private static Optional<Statement> nextUse(Node reading, String variable) {
        Optional<Statement> statement = Scopes.nearest(reading, Statement.class);
        Optional<BlockStmt> block = statement.flatMap(Node::getParentNode)
                .filter(BlockStmt.class::isInstance).map(BlockStmt.class::cast);
        if (statement.isEmpty() || block.isEmpty()) {
            return Optional.empty();
        }
        List<Statement> statements = block.get().getStatements();
        return statements.subList(statements.indexOf(statement.get()) + 1, statements.size()).stream()
                .filter(later -> later.findAll(NameExpr.class).stream().anyMatch(name -> isName(name, variable)))
                .findFirst();
    }

    private static boolean isName(Node node, String variable) {
        return node instanceof NameExpr name && name.getNameAsString().equals(variable);
    }
}
