package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Where the sources use constants, by name: whether they serve as keys of a format or as a type's encoding. */
final class ConstantUses {

    /** Methods that look a value up by its key. */
    private static final Pattern LOOKUP = Pattern.compile("(get|contains|remove|has)([A-Z].*)?");

    private final Map<String, List<Node>> uses;
    private final Converters converters;

    ConstantUses(JavaSources sources) {
        this.uses = sources.files().stream()
                .flatMap(file -> Stream.concat(file.unit().findAll(NameExpr.class).stream(),
                        file.unit().findAll(FieldAccessExpr.class).stream()))
                .collect(Collectors.groupingBy(use -> ((NodeWithSimpleName<?>) use).getNameAsString()));
        this.converters = new Converters(sources);
    }

    /**
     * Whether the constants are keys of a format rather than cases: every use passes one to a lookup, as {@code
     * attributes.getValue(ATTR_PREMAIN_CLASS)} and {@code config.get(CONFIG_TIMEOUT)} do, and none compares, switches
     * on or assigns one. Keys name different settings or headers, not values of one thing.
     */
    boolean areKeys(List<String> names) {
        List<Node> all = of(names);
        return !all.isEmpty() && all.stream().allMatch(ConstantUses::isLookupKey);
    }

    /** Whether every use sits in a converter, which turns outside input into a type: the constants are its encoding. */
    boolean encode(List<String> names) {
        List<Node> all = of(names);
        return !all.isEmpty() && all.stream().allMatch(converters::contain);
    }

    private List<Node> of(List<String> names) {
        return names.stream().flatMap(name -> uses.getOrDefault(name, List.of()).stream()).toList();
    }

    /**
     * Whether the use passes the constant as a key: to a lookup, as {@code get}, {@code contains}, {@code remove} and
     * {@code has} are, or with what it names, as {@code put(KEY, value)}, {@code setProperty(KEY, value)} and
     * {@code new Option(KEY, description)} take it. A call given only the constant, as a setter is, assigns a case.
     */
    private static boolean isLookupKey(Node use) {
        return use.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr call && call.getArguments().contains(use)
                        && (LOOKUP.matcher(call.getNameAsString()).matches() || call.getArguments().size() > 1)
                        || parent instanceof ObjectCreationExpr creation && creation.getArguments().contains(use)
                                && creation.getArguments().size() > 1)
                .isPresent();
    }
}
