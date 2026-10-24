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
 * default method, overridable method of an abstract class or method a subclass overrides is usually a deliberate
 * no-op, and so is a method whose Javadoc says it does nothing. An AspectJ {@code @Pointcut} declares a pointcut, so
 * its body is empty by design.
 */
final class EmptyMethods implements SourceFinder {

    private static final Pattern DOES_NOTHING = Pattern.compile("(?i)\\b(does|do) nothing\\b|\\bno-?op\\b|\\bnothing to\\b");

    private final String rule;
    private final OverriddenMethods overridden;

    /**
     * Configures the finder.
     *
     * @param rule       the rule id its violations carry
     * @param overridden the methods the project's subclasses override
     */
    EmptyMethods(String rule, OverriddenMethods overridden) {
        this.rule = rule;
        this.overridden = overridden;
    }

    @Override
    public Stream<Violation> find(ParsedFile file, ClassFilter filter) {
        return file.unit().findAll(MethodDeclaration.class).stream()
                .filter(this::hasEmptyBody)
                .filter(method -> Scopes.selected(filter, file, method))
                .map(method -> Scopes.violation(rule, file, method.getName(),
                        Scopes.describe(method) + " has an empty body",
                        "implement it, or say in a comment inside the body why doing nothing is correct"));
    }

    private boolean hasEmptyBody(MethodDeclaration method) {
        Optional<BlockStmt> body = method.getBody();
        return body.isPresent()
                && body.get().getStatements().isEmpty()
                && body.get().getAllContainedComments().isEmpty()
                && body.get().getOrphanComments().isEmpty()
                && !Hooks.javadocSays(method, DOES_NOTHING)
                && !Hooks.isHook(method, overridden)
                && !Scopes.hasAnnotation(method, "Pointcut");
    }
}
