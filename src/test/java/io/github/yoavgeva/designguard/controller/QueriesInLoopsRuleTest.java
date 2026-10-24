package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs once for each Java version's class files: see {@link CompiledProject#releases()}. */
@ParameterizedClass(name = "class files for Java {0}")
@MethodSource("io.github.yoavgeva.designguard.CompiledProject#releases")
class QueriesInLoopsRuleTest {

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void queriesRunOnceNotOncePerItem() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("PriceRepository", "package shop;\n\npublic interface PriceRepository {\n"
                        + "    long priceOf(String item);\n\n    void save(String item);\n}\n")
                .add("EnvPrices", """
                        package shop;

                        public final class EnvPrices implements PriceRepository {
                            @Override
                            public long priceOf(String item) {
                                return System.getenv(item).length();
                            }

                            @Override
                            public void save(String item) {
                                System.out.println(item);
                            }
                        }
                        """)
                .add("Checkout", """
                        package shop;

                        import java.util.List;

                        public class Checkout {
                            private static final PriceRepository FALLBACK = new EnvPrices();

                            private final PriceRepository prices;

                            public Checkout(PriceRepository prices) {
                                this.prices = prices;
                            }

                            public long total(List<String> items) {
                                long total = 0;
                                for (String item : items) {
                                    total += prices.priceOf(item);
                                }
                                return total;
                            }

                            public List<Long> each(List<String> items) {
                                return items.stream().map(prices::priceOf).toList();
                            }

                            public long sum(List<String> items) {
                                return items.stream().mapToLong(item -> prices.priceOf(item)).sum();
                            }

                            public void saveAll(List<String> items) {
                                items.forEach(prices::save);
                                for (String item : items) {
                                    prices.save(item);
                                }
                            }

                            public long eachRepository(List<PriceRepository> repositories) {
                                long total = 0;
                                for (PriceRepository repository : repositories) {
                                    total += repository.priceOf("book");
                                }
                                return total;
                            }

                            public long streamed(PriceRepository source, List<String> items) {
                                long total = 0;
                                for (String item : items) {
                                    total += source.priceOf(item);
                                }
                                return total;
                            }

                            public long one(String item) {
                                return prices.priceOf(item);
                            }

                            public int lengths(List<String> items) {
                                int length = 0;
                                for (String item : items) {
                                    length += item.length();
                                }
                                return length;
                            }

                            public long viaGetter(List<String> items) {
                                long total = 0;
                                for (String item : items) {
                                    total += repository().priceOf(item);
                                }
                                return total;
                            }

                            public long viaLocal(List<String> items) {
                                PriceRepository repository = this.prices;
                                long total = 0;
                                for (String item : items) {
                                    total += repository.priceOf(item);
                                }
                                return total;
                            }

                            public long viaStaticField(List<String> items) {
                                long total = 0;
                                for (String item : items) {
                                    total += FALLBACK.priceOf(item);
                                }
                                return total;
                            }

                            public long captured(PriceRepository source, List<String> items) {
                                return items.stream().mapToLong(item -> source.priceOf(item)).sum()
                                        + items.stream().map(source::priceOf).count();
                            }

                            public long nested(List<List<String>> orders) {
                                long total = 0;
                                for (List<String> order : orders) {
                                    for (String item : order) {
                                        total += item.length();
                                    }
                                }
                                return total;
                            }

                            public long keyed(List<String> items) {
                                long total = 0;
                                for (int index = 0; index < items.size(); index++) {
                                    StringBuilder key = new StringBuilder("price:");
                                    key.append(items.get(index));
                                    total += prices.priceOf(key.toString()) + prices.priceOf(new String("#" + (index + 1)));
                                }
                                return total;
                            }

                            private PriceRepository repository() {
                                return prices;
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.dependencies().noQueriesInLoops().evaluate()).extracting(Violation::message).containsExactly(
                "Checkout.total() queries PriceRepository.priceOf() inside a loop, once per item",
                "Checkout.each() queries PriceRepository.priceOf() inside a loop, once per item",
                "Checkout.sum() queries PriceRepository.priceOf() inside a loop, once per item",
                "Checkout.streamed() queries PriceRepository.priceOf() inside a loop, once per item",
                "Checkout.viaGetter() queries PriceRepository.priceOf() inside a loop, once per item",
                "Checkout.viaLocal() queries PriceRepository.priceOf() inside a loop, once per item",
                "Checkout.viaStaticField() queries PriceRepository.priceOf() inside a loop, once per item",
                "Checkout.captured() queries PriceRepository.priceOf() inside a loop, once per item",
                "Checkout.keyed() queries PriceRepository.priceOf() inside a loop, once per item");
        assertThat(guard.dependencies().noQueriesInLoops().evaluate().get(0).fix()).isEqualTo("load what the loop"
                + " needs with one call before it, such as one query for all the keys");
    }

    @Test
    void strategiesAndReadingAStreamAreNotQueries() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Rule", "package shop;\n\npublic interface Rule {\n    boolean test(String value);\n}\n")
                .add("LengthRule", """
                        package shop;

                        public final class LengthRule implements Rule {
                            @Override
                            public boolean test(String value) {
                                return value.length() > 3;
                            }
                        }
                        """)
                .add("EnvRule", """
                        package shop;

                        public final class EnvRule implements Rule {
                            @Override
                            public boolean test(String value) {
                                return System.getenv(value) != null;
                            }
                        }
                        """)
                .add("Selection", """
                        package shop;

                        import java.util.List;

                        public class Selection {
                            private final Rule rule;

                            public Selection(Rule rule) {
                                this.rule = rule;
                            }

                            public long matching(List<String> items) {
                                long count = 0;
                                for (String item : items) {
                                    count += rule.test(item) ? 1 : 0;
                                }
                                return count + items.stream().filter(rule::test).count();
                            }
                        }
                        """)
                .add("Input", """
                        package shop;

                        public class Input {
                            public int read() {
                                return System.getenv("INPUT").length();
                            }

                            public int skip(String until) {
                                return System.getenv(until).length();
                            }
                        }
                        """)
                .add("Lexer", """
                        package shop;

                        import java.util.List;

                        public class Lexer {
                            public int tokens(Input input, List<String> items) {
                                int tokens = 0;
                                while (input.read() > 0) {
                                    tokens += input.skip("*/");
                                }
                                return tokens + items.stream().mapToInt(item -> input.read()).sum();
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.dependencies().noQueriesInLoops().evaluate()).isEmpty();
    }
}
