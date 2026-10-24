package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The methods that the project's subclasses override. A class's method counts if a class that extends it, directly
 * or further down, an anonymous class created from it, or a constant body of its enum declares a method with the same
 * name and number of parameters. Types are matched by simple name, so a name two types share can only exempt more.
 */
final class OverriddenMethods {

    private final Map<String, List<Subtype>> subtypesOf;

    private OverriddenMethods(Map<String, List<Subtype>> subtypesOf) {
        this.subtypesOf = Map.copyOf(subtypesOf);
    }

    /** Indexes the subtypes the sources declare, by the simple name of each type they extend or implement. */
    static OverriddenMethods in(JavaSources sources) {
        return new OverriddenMethods(sources.files().stream()
                .flatMap(file -> file.unit().findAll(Node.class, OverriddenMethods::isSubtype).stream())
                .flatMap(OverriddenMethods::supertypeEntries)
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList()))));
    }

    /** Whether a subtype of the method's class declares a method with its name and number of parameters. */
    boolean isOverridden(MethodDeclaration method) {
        String signature = signature(method);
        return method.getParentNode()
                .filter(parent -> parent instanceof TypeDeclaration<?>)
                .map(parent -> ((TypeDeclaration<?>) parent).getNameAsString())
                .filter(owner -> declaredBelow(owner).contains(signature))
                .isPresent();
    }

    private Set<String> declaredBelow(String type) {
        Set<String> visited = new HashSet<>(Set.of(type));
        Deque<String> pending = new ArrayDeque<>(visited);
        Set<String> signatures = new HashSet<>();
        while (!pending.isEmpty()) {
            for (Subtype subtype : subtypesOf.getOrDefault(pending.pop(), List.of())) {
                signatures.addAll(subtype.methods());
                if (!subtype.name().isEmpty() && visited.add(subtype.name())) {
                    pending.push(subtype.name());
                }
            }
        }
        return signatures;
    }

    private static boolean isSubtype(Node node) {
        return node instanceof ClassOrInterfaceDeclaration
                || node instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()
                || node instanceof EnumConstantDeclaration constant && !constant.getClassBody().isEmpty();
    }

    private static Stream<Map.Entry<String, Subtype>> supertypeEntries(Node node) {
        if (node instanceof ClassOrInterfaceDeclaration declaration) {
            Subtype subtype = new Subtype(declaration.getNameAsString(), signatures(declaration.getMembers()));
            return Stream.concat(declaration.getExtendedTypes().stream(), declaration.getImplementedTypes().stream())
                    .map(supertype -> Map.entry(supertype.getNameAsString(), subtype));
        }
        if (node instanceof ObjectCreationExpr creation) {
            return Stream.of(Map.entry(creation.getType().getNameAsString(),
                    new Subtype("", signatures(creation.getAnonymousClassBody().orElseThrow()))));
        }
        EnumConstantDeclaration constant = (EnumConstantDeclaration) node;
        return constant.getParentNode()
                .filter(parent -> parent instanceof TypeDeclaration<?>)
                .map(parent -> Map.entry(((TypeDeclaration<?>) parent).getNameAsString(),
                        new Subtype("", signatures(constant.getClassBody()))))
                .stream();
    }

    private static Set<String> signatures(List<BodyDeclaration<?>> members) {
        return members.stream()
                .filter(MethodDeclaration.class::isInstance)
                .map(member -> signature((MethodDeclaration) member))
                .collect(Collectors.toSet());
    }

    private static String signature(MethodDeclaration method) {
        return method.getNameAsString() + "/" + method.getParameters().size();
    }

    /**
     * A type that extends or implements another.
     *
     * @param name    its simple name; empty for an anonymous class or an enum constant's body
     * @param methods the methods it declares, as name and number of parameters
     */
    private record Subtype(String name, Set<String> methods) {

        private Subtype {
            methods = Set.copyOf(methods);
        }
    }
}
