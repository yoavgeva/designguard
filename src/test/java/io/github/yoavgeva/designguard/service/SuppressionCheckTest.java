package io.github.yoavgeva.designguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuppressionCheckTest {

    @TempDir
    private Path root;

    @BeforeEach
    void writeSources() {
        new SourceTree(root).add("Cart", """
                package shop;

                import java.util.List;

                @SuppressWarnings({"unchecked", "serial"})
                class Cart {
                    @SuppressFBWarnings("EI_EXPOSE_REP")
                    List<Object> items;

                    int total() { // NOPMD
                        return 3; // NOSONAR
                    }

                    //noinspection ConstantConditions
                    @SuppressWarnings(value = "rawtypes")
                    List raw() {
                        return items;
                    }

                    boolean same(Object other) {
                        return this == other; // NOSONARLINT identity on purpose
                    }
                }
                """);
    }

    @Test
    void reportsEverySuppression() {
        assertThat(violations(Set.of())).extracting(Violation::line, Violation::message).containsExactly(
                tuple(5, "@SuppressWarnings(\"unchecked\", \"serial\") silences a warning"
                        + " instead of fixing it"),
                tuple(7, "@SuppressFBWarnings silences a warning instead of fixing it"),
                tuple(10, "NOPMD silences a warning instead of fixing it"),
                tuple(11, "NOSONAR silences a warning instead of fixing it"),
                tuple(14, "noinspection silences a warning instead of fixing it"),
                tuple(15, "@SuppressWarnings(\"rawtypes\") silences a warning instead of fixing it"),
                tuple(21, "NOSONARLINT silences a warning instead of fixing it"));
    }

    @Test
    void allowedNamesAreNotReported() {
        assertThat(violations(Set.of("serial", "NOSONAR", "rawtypes", "SuppressFBWarnings")))
                .extracting(Violation::message).containsExactly(
                        "@SuppressWarnings(\"unchecked\") silences a warning instead of fixing it",
                        "NOPMD silences a warning instead of fixing it",
                        "noinspection silences a warning instead of fixing it");
    }

    @Test
    void ignoresDocumentationThatMentionsTheMarkers(@TempDir Path other) {
        new SourceTree(other).add("Linter", """
                package shop;

                /** Reports {@code NOPMD} and {@code NOSONAR} comments, and explains noinspection. */
                class Linter {
                }
                """);

        assertThat(new SuppressionCheck(Set.of()).check(SourceReader.read(List.of(other)), ClassFilter.ALL)).isEmpty();
    }

    @Test
    void suggestsFixingTheCause() {
        assertThat(violations(Set.of()).get(0)).satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo("no-suppressions");
            assertThat(violation.fix()).isEqualTo("fix what the tool reports instead of silencing it");
        });
    }

    private List<Violation> violations(Set<String> allowed) {
        return new SuppressionCheck(allowed).check(SourceReader.read(List.of(root)), ClassFilter.ALL);
    }
}
