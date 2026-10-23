package io.github.yoavgeva.designguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataClassCheckTest {

    private static final String MONEY = "Money";

    @TempDir
    private Path root;

    @Test
    void reportsAClassThatOnlyCarriesData() {
        assertThat(violations("""
                package shop;

                import java.math.BigDecimal;
                import java.util.Objects;

                public final class Money {
                    private final BigDecimal amount;
                    private final String currency;

                    public Money(BigDecimal amount, String currency) {
                        this.amount = Objects.requireNonNull(amount);
                        this.currency = currency;
                    }

                    public BigDecimal getAmount() {
                        return amount;
                    }

                    public String currency() {
                        return this.currency;
                    }

                    @Override
                    public boolean equals(Object other) {
                        return other instanceof Money money && amount.equals(money.amount);
                    }

                    @Override
                    public int hashCode() {
                        return amount.hashCode();
                    }

                    @Override
                    public String toString() {
                        return amount + " " + currency;
                    }

                    public static Money zero(String currency) {
                        return new Money(BigDecimal.ZERO, currency);
                    }
                }
                """)).singleElement().satisfies(violation -> {
                    assertThat(violation.rule()).isEqualTo("data-classes-are-records");
                    assertThat(violation.line()).isEqualTo(6);
                    assertThat(violation.message()).isEqualTo("Money only carries data: make it a record");
                    assertThat(violation.fix()).isEqualTo("replace it with record Money(BigDecimal amount, String currency)");
                });
    }

    @Test
    void ignoresClassesWithBehaviour() {
        assertThat(violations("""
                package shop;

                public final class Money {
                    private final long cents;

                    public Money(long cents) {
                        this.cents = cents;
                    }

                    public Money plus(Money other) {
                        return new Money(cents + other.cents);
                    }
                }
                """)).isEmpty();
    }

    @Test
    void ignoresMutableAndInheritingClasses() {
        new SourceTree(root)
                .add("Base", "package shop;\n\npublic class Base {\n}\n")
                .add("Child", """
                        package shop;

                        public class Child extends Base {
                            private final int size;

                            public Child(int size) {
                                this.size = size;
                            }

                            public int size() {
                                return size;
                            }
                        }
                        """);

        assertThat(violations(MONEY, """
                package shop;

                public class Money {
                    private long cents;

                    public long cents() {
                        return cents;
                    }
                }
                """)).isEmpty();
    }

    @Test
    void ignoresPersistenceEntities() {
        assertThat(violations("""
                package shop;

                @Entity
                public class Money {
                    private final long cents;

                    public Money(long cents) {
                        this.cents = cents;
                    }

                    public long cents() {
                        return cents;
                    }
                }
                """)).isEmpty();
    }

    private List<Violation> violations(String source) {
        return violations(MONEY, source);
    }

    private List<Violation> violations(String typeName, String source) {
        new SourceTree(root).add(typeName, source);
        return new DataClassCheck().check(SourceReader.read(List.of(root)), ClassFilter.ALL);
    }
}
