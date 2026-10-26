package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.Type;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds money stored as a floating-point number: a {@code float}, {@code double}, {@code Float} or {@code Double}
 * field or record component whose name names an amount of money, as {@code totalPrice} and {@code shippingFee} do,
 * and not a ratio or count, as {@code taxRate} and {@code feePercent} do. Constants pass.
 */
public final class FloatingPointMoneyCheck {

    private static final String RULE = "no-floating-point-money";
    private static final Set<String> FLOATING = Set.of("float", "double", "Float", "Double", "java.lang.Float",
            "java.lang.Double");
    /** Words that name an amount of money in a name, as {@code totalPrice} and {@code shippingFee} have. */
    private static final Set<String> MONEY = Set.of("price", "prices", "amount", "amounts", "cost", "costs", "fee", "fees",
            "balance", "balances", "salary", "salaries", "tax", "taxes", "revenue", "budget", "payment", "payments",
            "refund", "refunds", "payout", "payouts", "wage", "wages", "money", "cash", "earnings", "income", "expense",
            "expenses", "spend", "spent", "subtotal", "cents");
    /** Words that make such a name a ratio or count, as {@code taxRate} and {@code feePercent} do. */
    private static final Set<String> NOT_MONEY = Set.of("rate", "rates", "ratio", "percent", "percentage", "pct",
            "factor", "weight", "score", "count", "multiplier", "share");

    /**
     * Finds violations in the sources the filter selects.
     *
     * @param sources the parsed production sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .flatMap(file -> file.unit().findAll(TypeDeclaration.class).stream()
                        .filter(type -> Scopes.selected(filter, file, type))
                        .flatMap(type -> stored(type)
                                .filter(value -> FLOATING.contains(value.type().asString()) && isMoney(value.name())
                                        && Scopes.selected(filter, file, value.node()))
                                .map(value -> Scopes.violation(RULE, file, value.node(), type.getNameAsString()
                                                + " stores " + value.name() + " as a floating-point number, which can't hold "
                                                + "most decimal amounts exactly, so sums and comparisons drift by fractions "
                                                + "of a cent",
                                        "store it as a BigDecimal, or as a long count of cents"))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /** The type's own instance fields and record components. */
    private static Stream<Stored> stored(TypeDeclaration<?> type) {
        Stream<Stored> fields = type.getMembers().stream()
                .filter(member -> member instanceof FieldDeclaration field && !field.isStatic())
                .map(FieldDeclaration.class::cast)
                .flatMap(field -> field.getVariables().stream())
                .map(variable -> new Stored(variable, variable.getNameAsString(), variable.getType()));
        Stream<Stored> components = type instanceof RecordDeclaration record
                ? record.getParameters().stream().map(parameter -> new Stored(parameter, parameter.getNameAsString(),
                        parameter.getType()))
                : Stream.empty();
        return Stream.concat(fields, components);
    }

    /** Whether a camelCase or snake_case name names an amount of money. */
    static boolean isMoney(String name) {
        List<String> words = Arrays.stream(name.split("(?<=[a-z0-9])(?=[A-Z])|_"))
                .map(word -> word.toLowerCase(Locale.ROOT))
                .toList();
        return words.stream().anyMatch(MONEY::contains) && words.stream().noneMatch(NOT_MONEY::contains);
    }

    /** A field's variable or a record component, with its name and declared type. */
    private record Stored(Node node, String name, Type type) {
    }
}
