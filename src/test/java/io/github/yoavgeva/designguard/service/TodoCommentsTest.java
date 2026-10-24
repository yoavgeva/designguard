package io.github.yoavgeva.designguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TodoCommentsTest {

    private static final String CART = "Cart";

    @TempDir
    private Path root;

    @Test
    void reportsTodoAndFixmeComments() {
        assertThat(violations(CART, """
                package shop;

                class Cart {
                    // TODO apply discounts
                    int total() {
                        return 3; /* FIXME rounding */
                    }
                }
                """)).extracting(Violation::line, Violation::message).containsExactly(
                        tuple(4, "TODO left in the code: TODO apply discounts"),
                        tuple(6, "FIXME left in the code: FIXME rounding"));
    }

    @Test
    void ignoresDocumentationThatMentionsTheMarkers() {
        assertThat(violations(CART, """
                package shop;

                /** Finds {@code TODO} and {@code FIXME} comments, the way a linter would. */
                class Cart {
                    /*
                     * TODO: split the totals out
                     */
                    int total() {
                        return 3; // explains why a FIXME marker matters
                    }
                }
                """)).extracting(Violation::line).containsExactly(5);
    }

    @Test
    void reportsMarkersAfterJavadocTagsHtmlAndCommentedOutSlashes() {
        assertThat(violations(CART, """
                package shop;

                class Cart {
                    /**
                     * Totals the cart.
                     *
                     * @deprecated TODO make private
                     */
                    int total() {
                        return 3;
                    }

                    /** <p>TODO move to Prices.</p> */
                    int tax() {
                        // // TODO: charge fees
                        return 1;
                    }
                }
                """)).extracting(Violation::line, Violation::message).containsExactly(
                        tuple(4, "TODO left in the code: @deprecated TODO make private"),
                        tuple(13, "TODO left in the code: <p>TODO move to Prices.</p>"),
                        tuple(15, "TODO left in the code: // TODO: charge fees"));
    }

    private List<Violation> violations(String typeName, String source) {
        new SourceTree(root).add(typeName, source);
        TodoComments todos = new TodoComments("no-stubs");
        return SourceReader.read(List.of(root)).files().stream()
                .flatMap(file -> todos.find(file, ClassFilter.ALL))
                .toList();
    }
}
