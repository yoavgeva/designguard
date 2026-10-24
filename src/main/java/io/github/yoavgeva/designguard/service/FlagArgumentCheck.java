package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds flag arguments: a boolean parameter whose only job is to pick between two behaviours, as in
 * {@code save(order, true)} followed by {@code if (async)}. Private methods, overrides and setters are skipped. A
 * boolean that only picks a value is data: {@code bool ? 1 : 0}, or an {@code if} and {@code else} that differ only in
 * a constant, as {@code out.append("true")} and {@code out.append("false")} do.
 */
public final class FlagArgumentCheck {

    private static final String RULE = "no-flag-arguments";
    private static final Pattern SETTER = Pattern.compile("set[A-Z].*");

    /**
     * Finds flag arguments in the sources the filter selects.
     *
     * @param sources the parsed sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .flatMap(file -> file.unit().findAll(MethodDeclaration.class).stream()
                        .filter(FlagArgumentCheck::canChooseItsSignature)
                        .filter(method -> Scopes.selected(filter, file, method))
                        .flatMap(method -> violations(file, method)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean canChooseItsSignature(MethodDeclaration method) {
        return !method.isPrivate() && method.getBody().isPresent() && !Scopes.hasAnnotation(method, "Override")
                && !SETTER.matcher(method.getNameAsString()).matches();
    }

    private static Stream<Violation> violations(ParsedFile file, MethodDeclaration method) {
        List<Expression> conditions = Stream.concat(
                        method.findAll(IfStmt.class).stream()
                                .filter(statement -> !isGuard(statement) && !ValueChoices.picksAValue(statement))
                                .map(IfStmt::getCondition),
                        method.findAll(ConditionalExpr.class).stream()
                                .filter(choice -> !ValueChoices.isValue(choice))
                                .map(ConditionalExpr::getCondition))
                .toList();
        return method.getParameters().stream()
                .filter(FlagArgumentCheck::isBoolean)
                .filter(parameter -> conditions.stream().anyMatch(condition -> isJust(condition, parameter)))
                .map(parameter -> Scopes.violation(RULE, file, method.getName(),
                        Scopes.describe(method) + " branches on its boolean parameter '" + parameter.getNameAsString() + "'",
                        "split it into one method per behaviour, so callers don't pass true or false"));
    }

    /** {@code if (!condition) throw ...;}: the boolean is the data a precondition checks, not a choice of behaviour. */
    private static boolean isGuard(IfStmt statement) {
        Statement then = statement.getThenStmt();
        boolean onlyThrows = then.isThrowStmt()
                || then.isBlockStmt() && then.asBlockStmt().getStatements().size() == 1
                        && then.asBlockStmt().getStatement(0).isThrowStmt();
        return onlyThrows && statement.getElseStmt().isEmpty();
    }

    private static boolean isBoolean(Parameter parameter) {
        String type = parameter.getType().asString();
        return "boolean".equals(type) || "Boolean".equals(type) || "java.lang.Boolean".equals(type);
    }

    private static boolean isJust(Expression condition, Parameter parameter) {
        Expression unwrapped = ValueChoices.unwrap(condition);
        if (unwrapped instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            unwrapped = ValueChoices.unwrap(unary.getExpression());
        }
        return unwrapped instanceof NameExpr name && name.getNameAsString().equals(parameter.getNameAsString());
    }
}
