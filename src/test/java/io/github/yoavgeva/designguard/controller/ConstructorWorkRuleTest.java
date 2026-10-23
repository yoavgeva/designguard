package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
class ConstructorWorkRuleTest {

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void constructorsAndStaticInitializersDoNoWork() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Storage", "package shop;\n\npublic interface Storage {\n    java.util.List<String> load();\n}\n")
                .add("DiskStorage", """
                        package shop;

                        import java.io.IOException;
                        import java.io.UncheckedIOException;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.List;

                        public final class DiskStorage implements Storage {
                            @Override
                            public List<String> load() {
                                try {
                                    return Files.readAllLines(Path.of("prices.txt"));
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }
                        }
                        """)
                .add("Catalog", """
                        package shop;

                        import java.io.IOException;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.List;

                        public class Catalog {
                            private final List<String> lines;

                            public Catalog(Path file) throws IOException {
                                this.lines = Files.readAllLines(file);
                            }

                            public List<String> lines() {
                                return lines;
                            }
                        }
                        """)
                .add("PriceCache", """
                        package shop;

                        import java.util.List;

                        public class PriceCache {
                            private final List<String> prices;

                            public PriceCache(Storage storage) {
                                this.prices = storage.load();
                            }

                            public List<String> prices() {
                                return prices;
                            }
                        }
                        """)
                .add("Reports", """
                        package shop;

                        public class Reports {
                            public Reports() {
                                refresh();
                            }

                            public final void refresh() {
                                System.out.println("refreshing");
                            }
                        }
                        """)
                .add("Config", """
                        package shop;

                        public final class Config {
                            public static final String REGION = System.getenv("REGION");

                            private Config() {
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.dependencies().constructorsDoNoWork().evaluate())
                .extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(12, "Catalog does work in its constructor through Files.readAllLines()"),
                        tuple(4, "Config does work in its static initializer through System.getenv()"),
                        tuple(9, "PriceCache does work in its constructor through Storage.load()"),
                        tuple(5, "Reports does work in its constructor through Reports.refresh()"));
        assertThat(guard.dependencies().constructorsDoNoWork().evaluate().get(0).fix()).isEqualTo(
                "pass in what Files.readAllLines() produces, or call it from a method when it's needed");
    }

    @Test
    void storingCollaboratorsValidatingAndCreatingObjectsIsNotWork() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Storage", "package shop;\n\npublic interface Storage {\n    java.util.List<String> load();\n}\n")
                .add("Orders", """
                        package shop;

                        import java.time.Clock;
                        import java.util.List;
                        import java.util.Objects;
                        import java.util.function.Supplier;

                        public class Orders {
                            private final Storage storage;
                            private final Clock clock;
                            private final Supplier<List<String>> reload;
                            private final List<String> audit = new java.util.ArrayList<>();
                            private final Runnable report = () -> System.out.println("orders");

                            public Orders(Storage storage, Clock clock) {
                                this.storage = Objects.requireNonNull(storage, "storage");
                                this.clock = clock;
                                this.reload = storage::load;
                            }

                            public List<String> all() {
                                audit.add(clock.instant().toString());
                                report.run();
                                return reload.get();
                            }
                        }
                        """)
                .add("LazyCatalog", """
                        package shop;

                        import java.io.IOException;
                        import java.io.UncheckedIOException;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.List;
                        import java.util.function.Supplier;

                        public final class LazyCatalog {
                            private final Supplier<List<String>> lines;

                            private LazyCatalog(Supplier<List<String>> lines) {
                                this.lines = lines;
                            }

                            public static LazyCatalog of(Path file) {
                                return new LazyCatalog(() -> {
                                    try {
                                        return Files.readAllLines(file);
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                });
                            }

                            public List<String> lines() {
                                return lines.get();
                            }
                        }
                        """)
                .add("Shop", """
                        package shop;

                        public class Shop {
                            private final LazyCatalog catalog;

                            public Shop(java.nio.file.Path file) {
                                this.catalog = LazyCatalog.of(file);
                            }

                            public int size() {
                                return catalog.lines().size();
                            }
                        }
                        """)
                .add("Money", """
                        package shop;

                        public record Money(long cents) {
                            public Money {
                                if (cents < 0) {
                                    throw new IllegalArgumentException("negative: " + cents);
                                }
                            }
                        }
                        """)
                .add("App", """
                        package shop;

                        public final class App {
                            private App() {
                                System.out.println("starting");
                            }

                            public static void main(String[] args) {
                                new App();
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.dependencies().constructorsDoNoWork().evaluate()).isEmpty();
    }
}
