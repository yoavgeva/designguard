package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code if (x instanceof A) ... else if (x instanceof B)} chains testing one expression against two or more types.
 * A single {@code instanceof}, and anything inside {@code equals}, is not dispatch.
 */
final class InstanceofChainSites implements SiteFinder {

    private final JavaSources sources;

    InstanceofChainSites(JavaSources sources) {
        this.sources = sources;
    }

    @Override
    public Stream<DispatchSite> find(ParsedFile file) {
        return file.unit().findAll(IfStmt.class).stream()
                .filter(IfChains::isChainHead)
                .filter(head -> !insideEqualsMethod(head))
                .flatMap(head -> site(file, head).stream());
    }

    private Optional<DispatchSite> site(ParsedFile file, IfStmt head) {
        Map<String, List<InstanceOfExpr>> checksByTarget = IfChains.chain(head).stream()
                .map(IfStmt::getCondition)
                .filter(InstanceOfExpr.class::isInstance)
                .map(InstanceOfExpr.class::cast)
                .collect(Collectors.groupingBy(check -> check.getExpression().toString(), LinkedHashMap::new,
                        Collectors.toList()));
        return checksByTarget.values().stream()
                .filter(checks -> checks.size() > 1)
                .findFirst()
                .flatMap(checks -> sources.projectTypeOf(checks.get(0).getExpression()))
                .map(type -> new DispatchSite(type, DispatchSite.Kind.INSTANCEOF_CHAIN, file, head,
                        false, false, type.encloses(head)));
    }

    private static boolean insideEqualsMethod(IfStmt ifStmt) {
        return Scopes.nearest(ifStmt, MethodDeclaration.class)
                .map(method -> "equals".equals(method.getNameAsString()))
                .orElse(false);
    }
}
