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

class RecordImmutabilityCheckTest {

    private static final String ORDER = "Order";

    @TempDir
    private Path root;

    @Test
    void reportsACollectionComponentThatIsNotCopied() {
        assertThat(violations("""
                package shop;

                import java.util.List;

                public record Order(String id, List<String> items) {
                }
                """)).singleElement().satisfies(violation -> {
                    assertThat(violation.rule()).isEqualTo("immutable-records");
                    assertThat(violation.line()).isEqualTo(5);
                    assertThat(violation.message()).isEqualTo(
                            "record Order's component 'items' (List<String>) can be changed by callers after construction");
                    assertThat(violation.fix()).isEqualTo("copy it in a compact constructor: items = List.copyOf(items);");
                });
    }

    @Test
    void acceptsComponentsCopiedInTheCompactConstructor() {
        assertThat(violations("""
                package shop;

                import java.util.List;
                import java.util.Map;

                public record Order(List<String> items, Map<String, Integer> counts) {
                    public Order {
                        items = List.copyOf(items);
                        counts = Map.copyOf(counts);
                    }
                }
                """)).isEmpty();
    }

    @Test
    void acceptsComponentsCopiedInTheCanonicalConstructor() {
        assertThat(violations("""
                package shop;

                import java.util.Collections;
                import java.util.Set;

                public record Order(Set<String> tags) {
                    public Order(Set<String> tags) {
                        this.tags = Collections.unmodifiableSet(new java.util.HashSet<>(tags));
                    }
                }
                """)).isEmpty();
    }

    @Test
    void suggestsTheRightCopyForEachMutableType() {
        assertThat(violations("""
                package shop;

                import java.util.Date;
                import java.util.Map;

                public record Order(Map<String, Integer> counts, byte[] payload, Date placed) {
                }
                """)).extracting(Violation::fix).containsExactly(
                        "copy it in a compact constructor: counts = Map.copyOf(counts);",
                        "use a List instead of an array, and copy it with List.copyOf",
                        "store an Instant instead: java.util.Date is mutable");
    }

    @Test
    void ignoresImmutableComponents() {
        assertThat(violations("""
                package shop;

                import java.time.Instant;
                import java.util.Optional;

                public record Order(String id, long cents, Instant placed, Optional<String> note) {
                }
                """)).isEmpty();
    }

    @Test
    void reportsUnmodifiableViewsAndMutableClassesOfTheProject() {
        new SourceTree(root)
                .add("Customer", """
                        package shop;

                        public class Customer {
                            private String name;

                            public String name() {
                                return name;
                            }

                            public void setName(String name) {
                                this.name = name;
                            }
                        }
                        """)
                .add("Address", """
                        package shop;

                        public final class Address {
                            private final String street;

                            public Address(String street) {
                                this.street = street;
                            }

                            public String street() {
                                return street;
                            }
                        }
                        """);

        assertThat(violations("""
                package shop;

                import java.util.Collections;
                import java.util.List;

                public record Order(Customer customer, Address address, List<String> items) {
                    public Order {
                        items = Collections.unmodifiableList(items);
                    }
                }
                """)).extracting(Violation::message, Violation::fix).containsExactly(
                        tuple("record Order's component 'customer' (Customer) can be changed by callers after construction",
                                "make Customer immutable, or store an immutable value instead: callers can still call its"
                                        + " setters"),
                        tuple("record Order's component 'items' (List<String>) can be changed by callers after construction",
                                "copy it in a compact constructor: items = List.copyOf(items);"));
    }

    private List<Violation> violations(String source) {
        new SourceTree(root).add(ORDER, source);
        return new RecordImmutabilityCheck().check(SourceReader.read(List.of(root)), ClassFilter.ALL);
    }
}
