package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Readings of the clock, randomness or the environment a test only reports or ignores, as a benchmark that prints how
 * long work took does: the reading, and every value computed from it, ends up only in what the test prints or logs,
 * or nowhere, so it can't decide the outcome. Values are followed through local variables, the arrays, collections
 * and atomics the test fills with them, and the methods of the test class it passes them to or returns them from. A
 * value that reaches an assertion, a condition, a field or any other call, such as one of the code under test, counts.
 */
final class ReportedReadings {

    /** How many values to follow before giving up and counting the reading as used. */
    private static final int MOST_VALUES = 500;

    private final Deque<Expression> pending = new ArrayDeque<>();
    private final Set<Node> followed = new HashSet<>();
    /** Reads of the arrays, collections and atomics the values are stored in, whose sizes the values don't decide. */
    private final Set<Expression> holders = new HashSet<>();

    private ReportedReadings(Expression reading) {
        pending.push(reading);
    }

    /**
     * Whether the test only reports or ignores the value the reading gives.
     *
     * @param reading the call or creation that reads the clock, randomness or the environment
     * @return whether no assertion, condition or other code uses it
     */
    static boolean isOnlyReported(Node reading) {
        return reading instanceof Expression expression && new ReportedReadings(expression).followAll();
    }

    private boolean followAll() {
        int count = 0;
        while (!pending.isEmpty()) {
            count++;
            if (count > MOST_VALUES || !follow(pending.pop())) {
                return false;
            }
        }
        return true;
    }

    /** Follows a value one step, to what uses it; whether that use keeps it out of the outcome so far. */
    private boolean follow(Expression value) {
        Node parent = value.getParentNode().orElse(value);
        if (parent instanceof Expression expression) {
            return intoExpression(value, expression);
        }
        if (parent instanceof VariableDeclarator variable) {
            return variable.getParentNode().filter(VariableDeclarationExpr.class::isInstance).isPresent()
                    && intoUses(variable, false);
        }
        if (parent instanceof ForEachStmt loop) {
            return loop.getIterable().equals(value) && intoUses(loop.getVariableDeclarator(), false);
        }
        if (parent instanceof ReturnStmt returned) {
            return intoCallers(returned);
        }
        return parent instanceof ExpressionStmt;
    }

    private boolean intoExpression(Expression value, Expression parent) {
        if (parent instanceof MethodCallExpr call) {
            return intoCall(value, call);
        }
        if (parent instanceof AssignExpr assignment) {
            return !assignment.getValue().equals(value) || intoAssigned(assignment);
        }
        if (parent instanceof FieldAccessExpr field && holders.contains(value)
                && ReadingUses.isSize(field.getNameAsString())) {
            return true;
        }
        boolean computed = parent instanceof BinaryExpr || parent instanceof UnaryExpr || parent instanceof CastExpr
                || parent instanceof EnclosedExpr || parent instanceof ArrayAccessExpr || parent instanceof FieldAccessExpr
                || parent instanceof ConditionalExpr choice && !choice.getCondition().equals(value);
        return computed && next(parent);
    }

    /** Follows a value a call takes: into its result, the collection it stores it in, or the parameter it binds. */
    private boolean intoCall(Expression value, MethodCallExpr call) {
        if (call.getScope().filter(value::equals).isPresent()) {
            return holders.contains(value) && ReadingUses.isSize(call.getNameAsString()) || next(call);
        }
        if (ReadingUses.isReport(call)) {
            return true;
        }
        if (ReadingUses.isCalculation(call)) {
            return next(call);
        }
        if (ReadingUses.isStore(call)) {
            return call.getScope().filter(scope -> scope instanceof NameExpr name && intoLocal(name, true)).isPresent()
                    && next(call);
        }
        int index = call.getArguments().indexOf(value);
        List<MethodDeclaration> methods = ReadingUses.ownMethods(call);
        return !methods.isEmpty() && methods.stream()
                .allMatch(method -> index < method.getParameters().size() && intoUses(method.getParameter(index), false));
    }

    /** Follows a value assigned to a local variable, or to an element of a local array. */
    private boolean intoAssigned(AssignExpr assignment) {
        Expression target = assignment.getTarget();
        boolean element = target instanceof ArrayAccessExpr;
        Expression variable = element ? ((ArrayAccessExpr) target).getName() : target;
        return variable instanceof NameExpr name && intoLocal(name, element) && next(assignment);
    }

    private boolean intoLocal(NameExpr name, boolean holder) {
        return ReadingUses.declarationOf(name).filter(declaration -> intoUses(declaration, holder)).isPresent();
    }

    /**
     * Follows a variable or parameter into every expression that reads it, each once: a value it holds, or a holder
     * the values are stored in.
     */
    private boolean intoUses(Node declaration, boolean holder) {
        if (followed.add(declaration)) {
            List<NameExpr> uses = ReadingUses.usesOf(declaration);
            pending.addAll(uses);
            if (holder) {
                holders.addAll(uses);
            }
        }
        return true;
    }

    /** Follows a value a private method of the test class returns into the calls of that method. */
    private boolean intoCallers(ReturnStmt returned) {
        Optional<MethodDeclaration> method = ReadingUses.returningMethod(returned);
        Optional<List<MethodCallExpr>> calls = method.flatMap(ReadingUses::callsOf);
        if (calls.isEmpty()) {
            return false;
        }
        if (followed.add(method.get())) {
            pending.addAll(calls.get());
        }
        return true;
    }

    /** Follows the expression next, since its value comes from the one followed now. */
    private boolean next(Expression expression) {
        pending.add(expression);
        return true;
    }
}
