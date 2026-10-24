package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds comments with a line starting with a {@code TODO} or {@code FIXME} marker, after any Javadoc tag, HTML tag or
 * commented-out slashes, as in {@code @deprecated TODO} or {@code <p>TODO}; mentions in prose don't count.
 */
final class TodoComments implements SourceFinder {

    private static final Pattern TODO_OR_FIXME = Pattern.compile("^(?:(?:@\\w+|<[^>]*>|/{2,})\\s*)*(TODO|FIXME)\\b");
    private static final int MAX_QUOTE = 80;

    private final String rule;

    /**
     * Configures the finder.
     *
     * @param rule the rule id its violations carry
     */
    TodoComments(String rule) {
        this.rule = rule;
    }

    @Override
    public Stream<Violation> find(ParsedFile file, ClassFilter filter) {
        return file.unit().getAllComments().stream()
                .filter(comment -> Scopes.selected(filter, file, scopeOf(file, comment)))
                .flatMap(comment -> violation(file, comment).stream());
    }

    private Optional<Violation> violation(ParsedFile file, Comment comment) {
        return lines(comment.getContent())
                .filter(line -> TODO_OR_FIXME.matcher(line).find())
                .findFirst()
                .map(line -> {
                    Matcher matcher = TODO_OR_FIXME.matcher(line);
                    String marker = matcher.find() ? matcher.group(1) : "TODO";
                    String quote = line.length() > MAX_QUOTE ? line.substring(0, MAX_QUOTE) + "..." : line;
                    return Scopes.violation(rule, file, comment, marker + " left in the code: " + quote,
                            "finish the work now, or track it in an issue instead of a comment");
                });
    }

    private static Node scopeOf(ParsedFile file, Comment comment) {
        return comment.getCommentedNode()
                .or(() -> innermostTypeContaining(file, Scopes.line(comment)))
                .orElse(comment);
    }

    private static Optional<Node> innermostTypeContaining(ParsedFile file, int line) {
        return file.unit().findAll(Node.class, node -> node instanceof TypeDeclaration<?>
                        && node.getRange().map(range -> range.begin.line <= line && line <= range.end.line).orElse(false))
                .stream()
                .reduce((outer, inner) -> inner);
    }

    /** The comment's lines, without leading whitespace and Javadoc asterisks. A marker must start a line. */
    private static Stream<String> lines(String commentContent) {
        return commentContent.lines()
                .map(line -> line.strip().replaceFirst("^\\*+\\s*", ""))
                .filter(line -> !line.isEmpty());
    }
}
