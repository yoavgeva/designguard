package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds choices between strings that an enum models with type safety: a {@code switch} over three or more string
 * literals, or an {@code if}/{@code else if} chain comparing one value with three or more strings through
 * {@code equals}. A typo in a string compiles; a missing enum case doesn't. Converters, methods named {@code parse},
 * {@code from}, {@code valueOf}, {@code of} or {@code decode} that turn input strings into a type, are exempt.
 */
public final class StringlyTypedChoicesCheck {

    private static final String RULE = "no-stringly-typed-choices";
    private static final int MIN_STRINGS = 3;
    private static final Pattern CONVERTER = Pattern.compile("(parse|from|valueOf|of|decode).*");
    private static final Pattern NOT_IDENTIFIER = Pattern.compile("[^A-Za-z0-9]+");
    private static final Set<String> EQUALS = Set.of("equals", "equalsIgnoreCase");

    /**
     * Finds violations in the sources the filter selects.
     *
     * @param sources the parsed production sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .flatMap(file -> Stream.concat(switches(file), chains(file))
                        .filter(choice -> Scopes.selected(filter, file, choice.node()) && !inConverter(choice.node()))
                        .map(choice -> choice.violation(file)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Stream<Choice> switches(ParsedFile file) {
        Stream<Choice> statements = file.unit().findAll(SwitchStmt.class).stream()
                .flatMap(choice -> overStrings(choice, choice.getSelector(), choice.getEntries()).stream());
        Stream<Choice> expressions = file.unit().findAll(SwitchExpr.class).stream()
                .flatMap(choice -> overStrings(choice, choice.getSelector(), choice.getEntries()).stream());
        return Stream.concat(statements, expressions);
    }

    private static Optional<Choice> overStrings(Node node, Expression selector, List<SwitchEntry> entries) {
        List<Expression> labels = entries.stream().flatMap(entry -> entry.getLabels().stream()).toList();
        if (labels.size() < MIN_STRINGS || !labels.stream().allMatch(Expression::isStringLiteralExpr)) {
            return Optional.empty();
        }
        List<String> strings = labels.stream().map(label -> label.asStringLiteralExpr().asString()).distinct().toList();
        return Optional.of(new Choice(node, "switches on", subjectOf(selector), strings));
    }

    /** {@code if}/{@code else if} chains, each from its first {@code if}, comparing one value with three strings. */
    private static Stream<Choice> chains(ParsedFile file) {
        return file.unit().findAll(IfStmt.class).stream()
                .filter(statement -> !isElseOf(statement))
                .flatMap(head -> {
                    Map<String, List<String>> stringsBySubject = Stream.iterate(Optional.of(head), Optional::isPresent,
                                    current -> current.get().getElseStmt().filter(Statement::isIfStmt).map(Statement::asIfStmt))
                            .map(Optional::get)
                            .flatMap(statement -> comparison(statement.getCondition()).stream())
                            .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new,
                                    Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
                    return stringsBySubject.entrySet().stream()
                            .filter(entry -> entry.getValue().stream().distinct().count() >= MIN_STRINGS)
                            .limit(1)
                            .map(entry -> new Choice(head, "compares " + entry.getKey() + " with", entry.getKey(),
                                    entry.getValue().stream().distinct().toList()));
                });
    }

    private static boolean isElseOf(IfStmt statement) {
        return statement.getParentNode()
                .filter(IfStmt.class::isInstance)
                .flatMap(parent -> ((IfStmt) parent).getElseStmt())
                .filter(other -> other.getRange().equals(statement.getRange()))
                .isPresent();
    }

    /** The value and the string an {@code equals} call compares, either way round. */
    private static Optional<Map.Entry<String, String>> comparison(Expression condition) {
        Optional<MethodCallExpr> call = condition.toMethodCallExpr()
                .filter(equals -> EQUALS.contains(equals.getNameAsString()) && equals.getArguments().size() == 1);
        return call.flatMap(equals -> equals.getScope().flatMap(scope -> {
            Expression argument = equals.getArgument(0);
            if (argument.isStringLiteralExpr() && !scope.isStringLiteralExpr()) {
                return Optional.of(Map.entry(scope.toString(), argument.asStringLiteralExpr().asString()));
            }
            if (scope.isStringLiteralExpr() && !argument.isStringLiteralExpr()) {
                return Optional.of(Map.entry(argument.toString(), scope.asStringLiteralExpr().asString()));
            }
            return Optional.empty();
        }));
    }

    private static String subjectOf(Expression selector) {
        return selector instanceof NodeWithSimpleName<?> named ? named.getNameAsString() : selector.toString();
    }

    private static boolean inConverter(Node node) {
        return Scopes.enclosingCallable(node).filter(callable -> CONVERTER.matcher(callable.getNameAsString()).matches())
                .isPresent();
    }

    /**
     * A choice between strings.
     *
     * @param node    the switch, or the first if of the chain
     * @param verb    what the code does with the strings, e.g. {@code switches on}
     * @param subject the value chosen on, as written
     * @param strings the strings, in order
     */
    private record Choice(Node node, String verb, String subject, List<String> strings) {

        Choice {
            strings = List.copyOf(strings);
        }

        Violation violation(ParsedFile file) {
            String where = Scopes.enclosingCallable(node).map(Scopes::describe)
                    .orElseGet(() -> Scopes.enclosingType(node).map(type -> type.getNameAsString()).orElse("the code"));
            List<String> quoted = strings.stream().map(string -> "\"" + string + "\"").toList();
            String listed = String.join(", ", quoted.subList(0, quoted.size() - 1)) + " and " + quoted.get(quoted.size() - 1);
            String constants = strings.stream()
                    .map(string -> NOT_IDENTIFIER.matcher(string.toUpperCase(Locale.ROOT)).replaceAll("_"))
                    .collect(Collectors.joining(", "));
            return Scopes.violation(RULE, file, node, where + " " + verb + " the strings " + listed + ": make them an enum",
                    "replace the strings with an enum, e.g. enum " + enumName() + " { " + constants + " }, and switch over it");
        }

        private String enumName() {
            String name = subject.replace("()", "");
            String simple = name.substring(name.lastIndexOf('.') + 1);
            return simple.isEmpty() ? "Choice" : Character.toUpperCase(simple.charAt(0)) + simple.substring(1);
        }
    }
}
