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
                        }
                        """)
                .guard("shop");

        assertThat(guard.dependencies().noQueriesInLoops().evaluate()).extracting(Violation::message).containsExactly(
                "Checkout.total() queries PriceRepository.priceOf() inside a loop, once per item",
                "Checkout.each() queries PriceRepository.priceOf() inside a loop, once per item",
                "Checkout.sum() queries PriceRepository.priceOf() inside a loop, once per item");
        assertThat(guard.dependencies().noQueriesInLoops().evaluate().get(0).fix()).isEqualTo("load what the loop"
                + " needs with one call before it, such as one query for all the keys");
    }
}
