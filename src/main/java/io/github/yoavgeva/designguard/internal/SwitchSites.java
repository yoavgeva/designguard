package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.PatternExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.nodeTypes.SwitchNode;
import com.github.javaparser.ast.stmt.SwitchEntry;
import java.util.Optional;
import java.util.stream.Stream;

/** Switches over a project type: with type or record patterns, or over enum constants. */
final class SwitchSites implements SiteFinder {

    private final JavaSources sources;

    SwitchSites(JavaSources sources) {
        this.sources = sources;
    }

    @Override
    public Stream<DispatchSite> find(ParsedFile file) {
        return file.unit().findAll(Node.class, SwitchNode.class::isInstance).stream()
                .flatMap(node -> site(file, node).stream());
    }

    private Optional<DispatchSite> site(ParsedFile file, Node node) {
        SwitchNode switchNode = (SwitchNode) node;
        boolean hasPatterns = switchNode.getEntries().stream()
                .flatMap(entry -> entry.getLabels().stream())
                .anyMatch(PatternExpr.class::isInstance);
        boolean hasDefault = switchNode.getEntries().stream().anyMatch(SwitchEntry::isDefault);
        boolean mustBeExhaustive = hasPatterns || node instanceof SwitchExpr;
        return sources.projectTypeOf(switchNode.getSelector())
                .filter(type -> hasPatterns || type.isEnum())
                .map(type -> new DispatchSite(type,
                        hasPatterns ? DispatchSite.Kind.TYPE_SWITCH : DispatchSite.Kind.ENUM_SWITCH,
                        file, node, mustBeExhaustive, hasDefault, type.encloses(node)));
    }
}
