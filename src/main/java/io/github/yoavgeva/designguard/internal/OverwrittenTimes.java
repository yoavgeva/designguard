package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Clock readings a test overwrites before using them: {@code Calendar day = Calendar.getInstance();} followed by
 * {@code day.clear()}, {@code day.setTime(...)}, {@code day.setTimeInMillis(...)} or sets of every field down to the
 * millisecond, or {@code new Date()} followed by {@code setTime(...)}. The value the clock gave is gone before anything
 * reads it.
 */
final class OverwrittenTimes {

    private static final Set<String> RESETS = Set.of("clear", "setTime", "setTimeInMillis");
    /** A calendar's fields that hold the time, from the year down to the millisecond. */
    private static final List<String> FIELDS = List.of("YEAR", "MONTH", "DATE", "HOUR_OF_DAY", "MINUTE", "SECOND",
            "MILLISECOND");
    /**
     * The numbers of arguments with which {@code set(...)} sets the fields from the year down, one for each argument, as
     * {@code set(2026, JANUARY, 1, 0, 0, 0)} does.
     */
    private static final Set<Integer> FROM_THE_YEAR = Set.of(3, 5, 6);
    /** {@code set(field, value)} sets the one field it names, as {@code set(MILLISECOND, 0)} does. */
    private static final int ONE_FIELD = 2;
    private static final Map<String, String> ALIASES = Map.of("DAY_OF_MONTH", "DATE");
    /** Calls that change how a calendar reads its fields, not what they hold. */
    private static final Set<String> SETTINGS = Set.of("setTimeZone", "setLenient");

    private OverwrittenTimes() {
    }

    /**
     * Whether the reading is stored in a variable whose uses, in the same block, reset its whole value before anything
     * reads it: one call that resets it, or calls that set every field from the year to the millisecond, as
     * {@code set(2026, JANUARY, 1, 0, 0, 0)} and {@code set(MILLISECOND, 0)} do.
     */
    static boolean isOverwritten(Node reading) {
        return storedIn(reading).filter(variable -> isResetBeforeUse(reading, variable)).isPresent();
    }

    private static boolean isResetBeforeUse(Node reading, String variable) {
        Set<String> set = new HashSet<>();
        for (Statement use : laterUses(reading, variable)) {
            Optional<MethodCallExpr> call = callOn(use, variable);
            String name = call.map(MethodCallExpr::getNameAsString).orElse("");
            if (RESETS.contains(name)) {
                return true;
            }
            if (!"set".equals(name) && !SETTINGS.contains(name)) {
                return false;
            }
            set.addAll(fieldsSet(call.get()));
            if (set.containsAll(FIELDS)) {
                return true;
            }
        }
        return false;
    }

    /** The call the statement makes on the variable, if that is all it does and its arguments don't read it. */
    private static Optional<MethodCallExpr> callOn(Statement use, String variable) {
        return Optional.of(use)
                .filter(Statement::isExpressionStmt)
                .map(statement -> statement.asExpressionStmt().getExpression())
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(call -> call.getScope().filter(scope -> isName(scope, variable)).isPresent()
                        && call.getArguments().stream().noneMatch(argument -> argument.findAll(NameExpr.class).stream()
                                .anyMatch(name -> isName(name, variable))));
    }

    /** The fields a {@code set(...)} call sets: by its number of arguments, or the field it names, as {@code MILLISECOND}. */
    private static List<String> fieldsSet(MethodCallExpr call) {
        int arguments = call.getArguments().size();
        if (FROM_THE_YEAR.contains(arguments)) {
            return FIELDS.subList(0, arguments);
        }
        if (arguments != ONE_FIELD) {
            return List.of();
        }
        Expression field = call.getArgument(0);
        String name = field instanceof FieldAccessExpr access ? access.getNameAsString()
                : field instanceof NameExpr simple ? simple.getNameAsString() : "";
        return List.of(ALIASES.getOrDefault(name, name));
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

    /** The statements after the reading's, in its block, that name the variable. */
    private static List<Statement> laterUses(Node reading, String variable) {
        Optional<Statement> statement = Scopes.nearest(reading, Statement.class);
        Optional<BlockStmt> block = statement.flatMap(Node::getParentNode)
                .filter(BlockStmt.class::isInstance).map(BlockStmt.class::cast);
        if (statement.isEmpty() || block.isEmpty()) {
            return List.of();
        }
        List<Statement> statements = block.get().getStatements();
        return statements.subList(statements.indexOf(statement.get()) + 1, statements.size()).stream()
                .filter(later -> later.findAll(NameExpr.class).stream().anyMatch(name -> isName(name, variable)))
                .toList();
    }

    private static boolean isName(Node node, String variable) {
        return node instanceof NameExpr name && name.getNameAsString().equals(variable);
    }
}
