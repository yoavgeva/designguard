package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds methods with an empty body and no comment explaining why. Hooks are allowed: an empty override, interface
 * default method or overridable method of an abstract class is usually a deliberate no-op, and so is a method whose
 * Javadoc says it does nothing.
 */
final class EmptyMethods implements SourceFinder {

    private static final Pattern DOES_NOTHING = Pattern.compile("(?i)\\b(does|do) nothing\\b|\\bno-?op\\b|\\bnothing to\\b");

    private final String rule;

    /**
     * Configures the finder.
     *
     * @param rule the rule id its violations carry
     */
    EmptyMethods(String rule) {
        this.rule = rule;
    }

    @Override
    public Stream<Violation> find(ParsedFile file, ClassFilter filter) {
        return file.unit().findAll(MethodDeclaration.class).stream()
                .filter(EmptyMethods::hasEmptyBody)
                .filter(method -> Scopes.selected(filter, file, method))
                .map(method -> Scopes.violation(rule, file, method.getName(),
                        Scopes.describe(method) + " has an empty body",
                        "implement it, or say in a comment inside the body why doing nothing is correct"));
    }

    private static boolean hasEmptyBody(MethodDeclaration method) {
        Optional<BlockStmt> body = method.getBody();
        return body.isPresent()
                && body.get().getStatements().isEmpty()
                && body.get().getAllContainedComments().isEmpty()
                && body.get().getOrphanComments().isEmpty()
                && !Hooks.javadocSays(method, DOES_NOTHING)
                && !Hooks.isHook(method);
    }
}
