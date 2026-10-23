package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Finds groups of constants that enumerate cases, which an enum models with type safety (Effective Java, item 34):
 * {@code static final} int, long or String constants of one type sharing a name prefix, like {@code STATUS_OPEN} and
 * {@code STATUS_PAID}, whose values are a run of numbers from 0 or 1, distinct bit flags, or the names themselves.
 * Constants with real values, like {@code MAX_ITEMS = 50} and {@code MAX_RETRIES = 3}, don't count.
 */
public final class EnumLikeConstantsCheck {

    private static final String RULE = "enums-over-constants";
    private static final String STRING = "String";
    private static final int SMALLEST_GROUP = 2;
    private static final Set<String> TYPES = Set.of("int", "long", STRING);
    private static final Pattern CONSTANT_NAME = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)+");
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");

    /**
     * Finds violations in the sources the filter selects.
     *
     * @param sources the parsed production sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .flatMap(file -> file.unit().findAll(ClassOrInterfaceDeclaration.class).stream()
                        .filter(type -> Scopes.selected(filter, file, type))
                        .flatMap(type -> groups(constants(type)).map(group -> violation(file, type, group))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static List<Constant> constants(ClassOrInterfaceDeclaration type) {
        return type.getFields().stream()
                .filter(field -> type.isInterface() || field.isStatic() && field.isFinal())
                .map(FieldDeclaration::getVariables)
                .flatMap(Collection::stream)
                .flatMap(variable -> constant(variable).stream())
                .toList();
    }

    private static Optional<Constant> constant(VariableDeclarator variable) {
        String name = variable.getNameAsString();
        String type = variable.getTypeAsString();
        if (!TYPES.contains(type) || !CONSTANT_NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return variable.getInitializer().flatMap(EnumLikeConstantsCheck::literal)
                .map(value -> new Constant(variable, name, type, value));
    }

    private static Optional<String> literal(Expression initializer) {
        if (initializer instanceof StringLiteralExpr text) {
            return Optional.of(text.asString());
        }
        if (initializer instanceof IntegerLiteralExpr number) {
            return Optional.of(String.valueOf(number.asNumber().longValue()));
        }
        if (initializer instanceof LongLiteralExpr number) {
            return Optional.of(String.valueOf(number.asNumber().longValue()));
        }
        return Optional.empty();
    }

    /** Groups sharing their first name segment, else their first two; each constant is reported once. */
    private static Stream<Group> groups(List<Constant> constants) {
        List<Group> broad = enumLike(constants, 1).toList();
        Set<String> reported = broad.stream().flatMap(group -> group.constants().stream())
                .map(Constant::name).collect(Collectors.toSet());
        Stream<Group> narrow = enumLike(constants, 2)
                .filter(group -> group.constants().stream().map(Constant::name).noneMatch(reported::contains));
        return Stream.concat(broad.stream(), narrow);
    }

    private static Stream<Group> enumLike(List<Constant> constants, int prefixSegments) {
        return constants.stream()
                .filter(constant -> constant.segments().length > prefixSegments)
                .collect(Collectors.groupingBy(constant -> constant.type() + " " + constant.prefix(prefixSegments),
                        LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(members -> new Group(members, prefixSegments))
                .filter(Group::enumeratesCases);
    }

    private static Violation violation(ParsedFile file, ClassOrInterfaceDeclaration type, Group group) {
        List<Constant> constants = group.constants();
        String names = constants.stream().map(Constant::name).collect(Collectors.joining(", "));
        String cases = constants.stream().map(constant -> constant.suffix(group.prefixSegments()))
                .collect(Collectors.joining(", "));
        return Scopes.violation(RULE, file, constants.get(0).node(),
                type.getNameAsString() + " declares " + names + " as " + constants.get(0).type()
                        + " constants: make them an enum",
                "replace them with an enum, e.g. enum " + group.enumName() + " { " + cases + " }");
    }

    /** A constant with a literal value, as source text: the string, or the number in decimal. */
    private record Constant(VariableDeclarator node, String name, String type, String value) {

        String[] segments() {
            return name.split("_");
        }

        String prefix(int count) {
            return String.join("_", Arrays.copyOf(segments(), count));
        }

        String suffix(int prefixSegments) {
            String[] segments = segments();
            return String.join("_", Arrays.copyOfRange(segments, prefixSegments, segments.length));
        }
    }

    /** Constants of one type sharing their first name segments. */
    private record Group(List<Constant> constants, int prefixSegments) {

        Group {
            constants = List.copyOf(constants);
        }

        boolean enumeratesCases() {
            if (constants.size() < SMALLEST_GROUP) {
                return false;
            }
            if (STRING.equals(constants.get(0).type())) {
                return constants.stream().allMatch(constant ->
                        normalized(constant.value()).equals(normalized(constant.suffix(prefixSegments))));
            }
            List<Long> values = constants.stream().map(constant -> Long.parseLong(constant.value())).sorted().toList();
            boolean distinct = values.stream().distinct().count() == values.size();
            long first = values.get(0);
            boolean run = (first == 0 || first == 1) && values.get(values.size() - 1) - first == values.size() - 1;
            boolean flags = values.stream().allMatch(value -> value > 0 && (value & (value - 1)) == 0);
            return distinct && (run || flags);
        }

        String enumName() {
            String[] segments = constants.get(0).segments();
            return IntStream.range(0, prefixSegments)
                    .mapToObj(index -> segments[index].charAt(0) + segments[index].substring(1).toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining());
        }

        private static String normalized(String text) {
            return NOT_ALPHANUMERIC.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
        }
    }
}
