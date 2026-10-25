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
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
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
                    && followOnce(variable, ReadingUses.usesOf(variable), false);
        }
        if (parent instanceof ForEachStmt loop) {
            VariableDeclarator element = loop.getVariableDeclarator();
            return loop.getIterable().equals(value) && followOnce(element, ReadingUses.usesOf(element), false);
        }
        if (parent instanceof ReturnStmt returned) {
            Optional<MethodDeclaration> method = ReadingUses.returningMethod(returned);
            return method.flatMap(ReadingUses::callsOf).filter(calls -> followOnce(method.get(), calls, false)).isPresent();
        }
        return parent instanceof ExpressionStmt statement
                && statement.getParentNode().filter(LambdaExpr.class::isInstance).isEmpty();
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
            return holders.contains(value) && ReadingUses.isSize(call.getNameAsString())
                    || call.getArguments().stream().allMatch(this::intoArgument) && next(call);
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
        return !methods.isEmpty() && methods.stream().allMatch(method -> index < method.getParameters().size()
                && followOnce(method.getParameter(index), ReadingUses.usesOf(method.getParameter(index)), false));
    }

    /**
     * Follows what a call on a value may give its argument: the data it fills a local array or collection with, as
     * {@code random.nextBytes(bytes)} does, or the values it passes a lambda, as {@code times.forEach(...)} does.
     * Fields but constants, elements and method references it may fill or run can't be followed.
     */
    private boolean intoArgument(Expression argument) {
        if (argument instanceof NameExpr name) {
            return intoLocal(name, true);
        }
        if (argument instanceof LambdaExpr lambda) {
            return lambda.getParameters().stream()
                    .allMatch(parameter -> followOnce(parameter, ReadingUses.usesOf(parameter), false));
        }
        boolean constant = argument instanceof FieldAccessExpr field && ReadingUses.isConstant(field.getNameAsString());
        return constant || !(argument instanceof FieldAccessExpr || argument instanceof ArrayAccessExpr
                || argument instanceof MethodReferenceExpr);
    }

    /** Follows a value assigned to a local variable, or to an element of a local array. */
    private boolean intoAssigned(AssignExpr assignment) {
        Expression target = assignment.getTarget();
        boolean element = target instanceof ArrayAccessExpr;
        Expression variable = element ? ((ArrayAccessExpr) target).getName() : target;
        return variable instanceof NameExpr name && intoLocal(name, element) && next(assignment);
    }

    /**
     * Follows the local variable the name reads. A holder values are stored in must be one the test creates there, as
     * {@code new long[5]}: one it got from elsewhere, as from {@code map.computeIfAbsent(...)}, may be read elsewhere.
     */
    private boolean intoLocal(NameExpr name, boolean holder) {
        return ReadingUses.declarationOf(name)
                .filter(declaration -> !holder || ReadingUses.isCreatedThere(declaration))
                .filter(declaration -> followOnce(declaration, ReadingUses.usesOf(declaration), holder))
                .isPresent();
    }

    /**
     * Follows the values next, once for each variable, parameter or method they come from: the reads of a value, or
     * of a holder the values are stored in, or the calls of a method returning one.
     */
    private boolean followOnce(Node source, List<? extends Expression> values, boolean holder) {
        if (followed.add(source)) {
            pending.addAll(values);
            if (holder) {
                holders.addAll(values);
            }
        }
        return true;
    }

    /** Follows the expression next, since its value comes from the one followed now. */
    private boolean next(Expression expression) {
        pending.add(expression);
        return true;
    }
}
