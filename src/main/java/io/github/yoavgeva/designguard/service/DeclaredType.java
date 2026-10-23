package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A type declared in the project's sources.
 *
 * @param file        the file declaring it
 * @param declaration its declaration
 */
record DeclaredType(ParsedFile file, TypeDeclaration<?> declaration) {

    String simpleName() {
        return declaration.getNameAsString();
    }

    boolean isEnum() {
        return declaration.isEnumDeclaration();
    }

    boolean isSealed() {
        return declaration instanceof ClassOrInterfaceDeclaration type && type.hasModifier(Modifier.Keyword.SEALED);
    }

    /** Whether the node is inside this type's declaration, including its nested types. */
    boolean encloses(Node node) {
        Optional<String> qualifiedName = declaration.getFullyQualifiedName();
        return Stream.iterate(Scopes.enclosingType(node), Optional::isPresent,
                        current -> current.flatMap(Scopes::enclosingType))
                .map(Optional::get)
                .map(TypeDeclaration::getFullyQualifiedName)
                .anyMatch(qualifiedName::equals);
    }
}
