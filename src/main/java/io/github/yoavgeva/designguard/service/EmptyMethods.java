package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds methods with an empty body and no comment explaining why. Overrides and interface default methods are
 * allowed: an empty one is usually a deliberate no-op hook.
 */
final class EmptyMethods implements SourceFinder {

    @Override
    public Stream<Violation> find(ParsedFile file, ClassFilter filter) {
        return file.unit().findAll(MethodDeclaration.class).stream()
                .filter(EmptyMethods::hasEmptyBody)
                .filter(method -> Scopes.selected(filter, file, method))
                .map(method -> Scopes.violation(StubCheck.RULE, file, method.getName(),
                        Scopes.describe(method) + " has an empty body",
                        "implement it, or say in a comment inside the body why doing nothing is correct"));
    }

    private static boolean hasEmptyBody(MethodDeclaration method) {
        Optional<BlockStmt> body = method.getBody();
        return body.isPresent()
                && body.get().getStatements().isEmpty()
                && body.get().getAllContainedComments().isEmpty()
                && body.get().getOrphanComments().isEmpty()
                && !method.isDefault()
                && !Scopes.hasAnnotation(method, "Override");
    }
}
