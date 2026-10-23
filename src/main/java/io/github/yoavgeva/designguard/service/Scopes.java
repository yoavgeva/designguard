package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Where a source node sits: its enclosing members, their annotations, and how to name it in a violation. */
final class Scopes {

    static final Comparator<Violation> BY_LOCATION = Comparator.comparing(Violation::file)
            .thenComparingInt(Violation::line)
            .thenComparing(Violation::message);

    private Scopes() {
    }

    /** Whether the filter selects the node, judged by its file's package and the annotations around it. */
    static boolean selected(ClassFilter filter, ParsedFile file, Node node) {
        return filter.selects(file.packageName(), annotationsAround(node));
    }

    /** Names of annotations on the node and on every member and type enclosing it, as written in source. */
    static List<String> annotationsAround(Node node) {
        return Stream.iterate(Optional.of(node), Optional::isPresent, current -> current.get().getParentNode())
                .map(Optional::get)
                .filter(NodeWithAnnotations.class::isInstance)
                .flatMap(scope -> ((NodeWithAnnotations<?>) scope).getAnnotations().stream())
                .map(AnnotationExpr::getNameAsString)
                .toList();
    }

    /** Names a method or constructor as {@code Type.method()}. */
    static String describe(CallableDeclaration<?> callable) {
        String owner = enclosingType(callable).map(type -> type.getNameAsString()).orElse("");
        return owner + "." + callable.getNameAsString() + "()";
    }

    /** The nearest method or constructor around the node, excluding the node itself. */
    static Optional<CallableDeclaration<?>> enclosingCallable(Node node) {
        return ancestors(node)
                .<CallableDeclaration<?>>mapMulti((ancestor, out) -> {
                    if (ancestor instanceof CallableDeclaration<?> callable) {
                        out.accept(callable);
                    }
                })
                .findFirst();
    }

    /** The nearest class, interface, enum or record around the node, excluding the node itself. */
    static Optional<TypeDeclaration<?>> enclosingType(Node node) {
        return ancestors(node)
                .<TypeDeclaration<?>>mapMulti((ancestor, out) -> {
                    if (ancestor instanceof TypeDeclaration<?> type) {
                        out.accept(type);
                    }
                })
                .findFirst();
    }

    /** The nearest ancestor of the given class, excluding the node itself. */
    static <N extends Node> Optional<N> nearest(Node node, Class<N> type) {
        return ancestors(node).filter(type::isInstance).map(type::cast).findFirst();
    }

    private static Stream<Node> ancestors(Node node) {
        return Stream.iterate(node.getParentNode(), Optional::isPresent, current -> current.get().getParentNode())
                .map(Optional::get);
    }

    /** Whether a member carries an annotation with the given simple name, written simply or qualified. */
    static boolean hasAnnotation(NodeWithAnnotations<?> member, String simpleName) {
        return member.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .anyMatch(name -> name.equals(simpleName) || name.endsWith("." + simpleName));
    }

    /** The 1-based line where the node starts, or 0 when unknown. */
    static int line(Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }

    /** A blocking violation located at the node. */
    static Violation violation(String rule, ParsedFile file, Node node, String message, String fix) {
        return new Violation(rule, Violation.Severity.ERROR, file.path(), line(node), message, fix);
    }
}
