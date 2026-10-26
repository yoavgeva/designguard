package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Navigates {@code if / else if} chains. Nodes are compared by position, because JavaParser's {@code equals} is
 * structural: two identical statements in one file would otherwise be the same.
 */
final class IfChains {

    private IfChains() {
    }

    /** The statement a node belongs to, widened to the head of its {@code else if} chain. */
    static Node branchOf(Node node) {
        Node statement = Scopes.nearest(node, Statement.class).map(Node.class::cast).orElse(node);
        Optional<IfStmt> parent = ifWhoseElseIs(statement);
        while (parent.isPresent()) {
            statement = parent.get();
            parent = ifWhoseElseIs(statement);
        }
        return statement;
    }

    /** Whether the if statement starts a chain, rather than being the {@code else if} of another. */
    static boolean isChainHead(IfStmt ifStmt) {
        return ifWhoseElseIs(ifStmt).isEmpty();
    }

    /** The head and every {@code else if} after it. */
    static List<IfStmt> chain(IfStmt head) {
        List<IfStmt> chain = new ArrayList<>();
        Optional<IfStmt> current = Optional.of(head);
        while (current.isPresent()) {
            chain.add(current.get());
            current = current.get().getElseStmt().filter(Statement::isIfStmt).map(Statement::asIfStmt);
        }
        return chain;
    }

    private static Optional<IfStmt> ifWhoseElseIs(Node node) {
        return node.getParentNode()
                .filter(IfStmt.class::isInstance)
                .map(IfStmt.class::cast)
                .filter(parent -> node.getRange().isPresent()
                        && parent.getElseStmt().flatMap(Node::getRange).equals(node.getRange()));
    }
}
