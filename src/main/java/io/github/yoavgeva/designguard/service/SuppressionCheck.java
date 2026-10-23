package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds warnings silenced instead of fixed: {@code @SuppressWarnings}, SpotBugs' {@code @SuppressFBWarnings}, and
 * comments starting with {@code NOPMD}, {@code NOSONAR}, {@code noinspection} or {@code CHECKSTYLE:OFF}; mentions in
 * prose don't count. Allowed names (warning names like {@code serial}, or markers like {@code NOSONAR}) are not
 * reported.
 */
public final class SuppressionCheck {

    private static final String RULE = "no-suppressions";
    private static final String FIX = "fix what the tool reports instead of silencing it";
    private static final String SILENCES = " silences a warning instead of fixing it";
    private static final String SPOTBUGS = "SuppressFBWarnings";
    private static final Pattern MARKER = Pattern.compile("^\\s*(?:(NOPMD|NOSONAR)|(noinspection)|((?i)CHECKSTYLE[:.]OFF))\\b");

    private final Set<String> allowed;

    /**
     * Configures the check.
     *
     * @param allowed warning names and markers that may be suppressed
     */
    public SuppressionCheck(Set<String> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    /**
     * Finds suppressions in the sources the filter selects.
     *
     * @param sources the parsed sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .flatMap(file -> Stream.concat(annotations(file, filter), comments(file, filter)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private Stream<Violation> annotations(ParsedFile file, ClassFilter filter) {
        return file.unit().findAll(AnnotationExpr.class).stream()
                .filter(annotation -> Scopes.selected(filter, file, annotation))
                .flatMap(annotation -> annotationViolation(file, annotation).stream());
    }

    private Optional<Violation> annotationViolation(ParsedFile file, AnnotationExpr annotation) {
        String name = UnitTestMethods.simpleName(annotation);
        if (SPOTBUGS.equals(name) && !allowed.contains(SPOTBUGS)) {
            return Optional.of(Scopes.violation(RULE, file, annotation, "@" + SPOTBUGS + SILENCES, FIX));
        }
        if (!"SuppressWarnings".equals(name)) {
            return Optional.empty();
        }
        List<String> silenced = AnnotationValues.strings(annotation, "value").stream()
                .filter(warning -> !allowed.contains(warning))
                .toList();
        if (silenced.isEmpty()) {
            return Optional.empty();
        }
        String warnings = silenced.stream().map(warning -> "\"" + warning + "\"").collect(Collectors.joining(", "));
        return Optional.of(Scopes.violation(RULE, file, annotation, "@SuppressWarnings(" + warnings + ")" + SILENCES, FIX));
    }

    private Stream<Violation> comments(ParsedFile file, ClassFilter filter) {
        return file.unit().getAllComments().stream()
                .filter(comment -> Scopes.selected(filter, file, comment.getCommentedNode().orElse(comment)))
                .flatMap(comment -> silencingMarker(comment).stream()
                        .map(marker -> Scopes.violation(RULE, file, comment, marker + SILENCES, FIX)));
    }

    private Optional<String> silencingMarker(Comment comment) {
        Matcher matcher = MARKER.matcher(comment.getContent());
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Stream.of(matcher.group(1), matcher.group(2), matcher.group(3))
                .filter(group -> group != null && !allowed.contains(group))
                .findFirst();
    }
}
