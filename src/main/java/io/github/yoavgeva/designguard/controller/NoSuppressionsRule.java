package io.github.yoavgeva.designguard.controller;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.service.CodeBase;
import io.github.yoavgeva.designguard.service.SuppressionCheck;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fails on warnings silenced instead of fixed, in production and test code: {@code @SuppressWarnings},
 * {@code @SuppressFBWarnings}, and {@code NOPMD}, {@code NOSONAR}, {@code noinspection} and
 * {@code CHECKSTYLE:OFF} comments.
 */
public final class NoSuppressionsRule extends DesignRule<NoSuppressionsRule> {

    private final Set<String> allowed;

    NoSuppressionsRule(CodeBase code, ClassFilter filter, Set<String> allowed) {
        super(code, filter);
        this.allowed = Set.copyOf(allowed);
    }

    /**
     * Allows suppressing some warnings, e.g. {@code "serial"}, or some markers, e.g. {@code "NOSONAR"}.
     *
     * @param names warning names or markers
     * @return the reconfigured rule
     */
    public NoSuppressionsRule allowing(String... names) {
        return new NoSuppressionsRule(code(), filter(),
                Stream.concat(allowed.stream(), Stream.of(names)).collect(Collectors.toSet()));
    }

    @Override
    List<Violation> violations(CodeBase code, ClassFilter filter) {
        return new SuppressionCheck(allowed).check(code.sources().plus(code.testSources()), filter);
    }

    @Override
    NoSuppressionsRule withFilter(ClassFilter filter) {
        return new NoSuppressionsRule(code(), filter, allowed);
    }
}
