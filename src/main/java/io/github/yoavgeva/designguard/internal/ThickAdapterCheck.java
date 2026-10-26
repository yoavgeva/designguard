package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.tngtech.archunit.core.domain.PackageMatcher;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds adapters that decide instead of delegating: methods and constructors in adapter packages, such as web
 * controllers or message listeners, that make more than two decisions ({@code if}s, ternaries, {@code switch} cases or
 * loops). An adapter translates between the outside and the application, and the decisions belong where they can be
 * tested without it. One or two checks, such as validating input, are fine.
 */
public final class ThickAdapterCheck {

    private static final String RULE = "thin-adapters";
    private static final int MAX_DECISIONS = 2;
    private static final List<Class<? extends Node>> DECISIONS = List.of(IfStmt.class, ConditionalExpr.class,
            ForStmt.class, ForEachStmt.class, WhileStmt.class, DoStmt.class);

    private final List<PackageMatcher> adapterPackages;

    /**
     * Configures the check.
     *
     * @param adapterPackages ArchUnit package patterns of the adapters, e.g. {@code ..web..}
     */
    public ThickAdapterCheck(List<String> adapterPackages) {
        this.adapterPackages = adapterPackages.stream().map(PackageMatcher::of).toList();
    }

    /**
     * Finds violations in the sources the filter selects.
     *
     * @param sources the parsed production sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .filter(file -> adapterPackages.stream().anyMatch(matcher -> matcher.matches(file.packageName())))
                .flatMap(file -> Stream.<CallableDeclaration<?>>concat(
                                file.unit().findAll(MethodDeclaration.class).stream(),
                                file.unit().findAll(ConstructorDeclaration.class).stream())
                        .filter(callable -> Scopes.selected(filter, file, callable))
                        .flatMap(callable -> violation(file, callable).stream()))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Optional<Violation> violation(ParsedFile file, CallableDeclaration<?> callable) {
        long decisions = callable.findAll(Node.class).stream()
                .filter(node -> DECISIONS.stream().anyMatch(kind -> kind.isInstance(node))
                        || node instanceof SwitchEntry entry && !entry.getLabels().isEmpty())
                .count();
        if (decisions <= MAX_DECISIONS) {
            return Optional.empty();
        }
        String name = Scopes.describe(callable);
        return Optional.of(Scopes.violation(RULE, file, callable.getName(),
                        name + " makes " + decisions + " decisions; an adapter should only translate and delegate",
                        "move the decisions into the application or domain layer, and call it from here")
                .identifiedAs(name + " makes decisions an adapter shouldn't"));
    }
}
