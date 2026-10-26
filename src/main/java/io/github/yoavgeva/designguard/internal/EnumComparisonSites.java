package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Comparisons against a project enum's constants: {@code x == Color.RED}, {@code x != RED},
 * {@code Color.RED.equals(x)}. All comparisons deciding one {@code if / else if} chain count as one site.
 */
final class EnumComparisonSites implements SiteFinder {

    private final JavaSources sources;

    EnumComparisonSites(JavaSources sources) {
        this.sources = sources;
    }

    @Override
    public Stream<DispatchSite> find(ParsedFile file) {
        Stream<Optional<Comparison>> operators = file.unit().findAll(BinaryExpr.class).stream()
                .filter(binary -> binary.getOperator() == BinaryExpr.Operator.EQUALS
                        || binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS)
                .map(binary -> comparison(binary, binary.getLeft(), binary.getRight()));
        Stream<Optional<Comparison>> equalsCalls = file.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> "equals".equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .map(call -> comparison(call, call.getScope().orElse(call), call.getArgument(0)));
        return Stream.concat(operators, equalsCalls)
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(Comparison::position, comparison -> comparison,
                        (first, second) -> first, LinkedHashMap::new))
                .values().stream()
                .map(comparison -> new DispatchSite(comparison.type(), DispatchSite.Kind.ENUM_COMPARISON, file,
                        comparison.branch(), false, false, comparison.type().encloses(comparison.branch())));
    }

    private Optional<Comparison> comparison(Node comparison, Expression left, Expression right) {
        return Resolution.enumOfConstant(left).or(() -> Resolution.enumOfConstant(right))
                .flatMap(sources::declaration)
                .map(type -> new Comparison(IfChains.branchOf(comparison), type));
    }

    /** One enum comparison and the branch it decides. */
    private record Comparison(Node branch, DeclaredType type) {

        Range position() {
            return branch.getRange().orElseThrow();
        }
    }
}
