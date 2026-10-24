package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds record components callers can still change: collections and maps not copied into unmodifiable ones in the
 * constructor (an unmodifiable view of the caller's collection isn't a copy), arrays, mutable JDK types like
 * {@code Date}, and the project's mutable data classes, those with setters or open fields. A record is only a value if
 * it is deeply immutable.
 */
public final class RecordImmutabilityCheck {

    private static final String RULE = "immutable-records";
    private static final Pattern SETTER = Pattern.compile("set[A-Z].*");
    private static final String LIST_COPY = "List.copyOf";
    private static final String SET_COPY = "Set.copyOf";
    private static final String MAP_COPY = "Map.copyOf";
    private static final Map<String, String> COPY_BY_TYPE = Map.ofEntries(
            Map.entry("List", LIST_COPY), Map.entry("Collection", LIST_COPY), Map.entry("Iterable", LIST_COPY),
            Map.entry("ArrayList", LIST_COPY), Map.entry("LinkedList", LIST_COPY), Map.entry("Queue", LIST_COPY),
            Map.entry("Deque", LIST_COPY), Map.entry("ArrayDeque", LIST_COPY),
            Map.entry("Set", SET_COPY), Map.entry("SortedSet", SET_COPY), Map.entry("NavigableSet", SET_COPY),
            Map.entry("HashSet", SET_COPY), Map.entry("LinkedHashSet", SET_COPY), Map.entry("TreeSet", SET_COPY),
            Map.entry("Map", MAP_COPY), Map.entry("SortedMap", MAP_COPY), Map.entry("NavigableMap", MAP_COPY),
            Map.entry("HashMap", MAP_COPY), Map.entry("LinkedHashMap", MAP_COPY), Map.entry("TreeMap", MAP_COPY));
    private static final Map<String, String> REPLACEMENT_BY_TYPE = Map.of(
            "Date", "store an Instant instead: java.util.Date is mutable",
            "Calendar", "store a java.time type instead: Calendar is mutable",
            "StringBuilder", "store a String instead: StringBuilder is mutable",
            "StringBuffer", "store a String instead: StringBuffer is mutable");
    private static final Set<String> COPY_METHODS = Set.of("copyOf", "toList", "unmodifiableList", "unmodifiableSet",
            "unmodifiableMap", "unmodifiableCollection", "unmodifiableSortedSet", "unmodifiableSortedMap",
            "unmodifiableNavigableSet", "unmodifiableNavigableMap", "unmodifiableSequencedCollection");

    /**
     * Finds mutable components of the records the filter selects.
     *
     * @param sources the parsed sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        Set<String> mutableClasses = mutableClasses(sources);
        return sources.files().stream()
                .flatMap(file -> file.unit().findAll(RecordDeclaration.class).stream()
                        .filter(record -> Scopes.selected(filter, file, record))
                        .flatMap(record -> record.getParameters().stream()
                                .flatMap(component -> violation(file, record, component, mutableClasses).stream())))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /**
     * The simple names of the project's mutable data classes, those with a setter or a field that isn't private or
     * final; a name two classes share counts only if both are mutable.
     */
    private static Set<String> mutableClasses(JavaSources sources) {
        Map<String, List<Boolean>> mutableByName = sources.files().stream()
                .flatMap(file -> file.unit().findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(type -> !type.isInterface())
                .collect(Collectors.groupingBy(ClassOrInterfaceDeclaration::getNameAsString,
                        Collectors.mapping(RecordImmutabilityCheck::isMutable, Collectors.toList())));
        return mutableByName.entrySet().stream()
                .filter(entry -> entry.getValue().stream().allMatch(mutable -> mutable))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static boolean isMutable(ClassOrInterfaceDeclaration type) {
        boolean openField = type.getFields().stream()
                .anyMatch(field -> !field.isStatic() && !field.isFinal() && !field.isPrivate());
        boolean setter = type.getMethods().stream()
                .anyMatch(method -> !method.isStatic() && !method.isPrivate() && SETTER.matcher(method.getNameAsString()).matches()
                        && !method.findAll(AssignExpr.class).isEmpty());
        return openField || setter;
    }

    private static Optional<Violation> violation(ParsedFile file, RecordDeclaration record, Parameter component,
            Set<String> mutableClasses) {
        Optional<String> fix = fixFor(record, component, mutableClasses);
        return fix.map(hint -> Scopes.violation(RULE, file, component,
                "record " + record.getNameAsString() + "'s component '" + component.getNameAsString() + "' ("
                        + component.getType().asString() + ") can be changed by callers after construction",
                hint));
    }

    private static Optional<String> fixFor(RecordDeclaration record, Parameter component, Set<String> mutableClasses) {
        Type type = component.getType();
        if (type.isArrayType()) {
            return Optional.of("use a List instead of an array, and copy it with List.copyOf");
        }
        if (!(type instanceof ClassOrInterfaceType classType)) {
            return Optional.empty();
        }
        String simpleName = classType.getNameAsString();
        String name = component.getNameAsString();
        if (REPLACEMENT_BY_TYPE.containsKey(simpleName)) {
            return Optional.of(REPLACEMENT_BY_TYPE.get(simpleName));
        }
        if (COPY_BY_TYPE.containsKey(simpleName) && !isCopied(record, name)) {
            return Optional.of("copy it in a compact constructor: " + name + " = "
                    + COPY_BY_TYPE.get(simpleName) + "(" + name + ");");
        }
        if (mutableClasses.contains(simpleName)) {
            return Optional.of("make " + simpleName + " immutable, or store an immutable value instead: callers can still"
                    + " call its setters");
        }
        return Optional.empty();
    }

    private static boolean isCopied(RecordDeclaration record, String component) {
        Stream<AssignExpr> compact = record.getCompactConstructors().stream()
                .flatMap(constructor -> constructor.findAll(AssignExpr.class).stream());
        Stream<AssignExpr> canonical = record.getConstructors().stream()
                .flatMap(constructor -> constructor.findAll(AssignExpr.class).stream());
        return Stream.concat(compact, canonical)
                .filter(assignment -> assigns(assignment.getTarget(), component))
                .anyMatch(assignment -> isUnmodifiableCopy(assignment.getValue(), component));
    }

    private static boolean assigns(Expression target, String component) {
        return target instanceof NameExpr name && name.getNameAsString().equals(component)
                || target instanceof FieldAccessExpr field && field.getNameAsString().equals(component);
    }

    /**
     * Whether the value is an unmodifiable copy. An unmodifiable view of the component itself, as
     * {@code Collections.unmodifiableList(items)} is, changes when the caller changes the list it was given.
     */
    private static boolean isUnmodifiableCopy(Expression value, String component) {
        return value.findAll(MethodCallExpr.class).stream().anyMatch(call -> COPY_METHODS.contains(call.getNameAsString())
                && !(call.getNameAsString().startsWith("unmodifiable") && call.getArguments().size() == 1
                        && assigns(call.getArgument(0), component)));
    }
}
