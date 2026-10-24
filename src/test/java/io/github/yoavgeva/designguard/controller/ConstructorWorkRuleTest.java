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
                .add("Regions", """
                        package shop;

                        public final class Regions {
                            private final String region = System.getenv("REGION");
                            private final int size;

                            public Regions() {
                                this(1);
                            }

                            public Regions(int size, String name) {
                                this.size = size + name.length();
                            }

                            public Regions(int size) {
                                this.size = size;
                            }

                            public String region() {
                                return region + size;
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
                .add("EnvDefaults", """
                        package shop;

                        public abstract class EnvDefaults {
                            public String region() {
                                return System.getenv("REGION");
                            }
                        }
                        """)
                .add("ShopDefaults", "package shop;\n\npublic final class ShopDefaults extends EnvDefaults {\n}\n")
                .add("Banner", """
                        package shop;

                        public class Banner {
                            private final String region;

                            public Banner(ShopDefaults defaults) {
                                this.region = defaults.region();
                            }

                            public String region() {
                                return region;
                            }
                        }
                        """)
                .add("Journal", """
                        package shop;

                        import java.io.FileNotFoundException;
                        import java.io.FileOutputStream;
                        import java.io.OutputStream;

                        public class Journal {
                            private final OutputStream out;

                            public Journal(String file) throws FileNotFoundException {
                                this.out = new FileOutputStream(file);
                            }

                            public OutputStream out() {
                                return out;
                            }
                        }
                        """)
                .add("Stamps", """
                        package shop;

                        import java.net.InetAddress;
                        import java.net.UnknownHostException;
                        import java.util.Calendar;
                        import java.util.Date;
                        import java.util.Properties;

                        public final class Stamps {
                            private final Date created = new Date();
                            private final Calendar today = Calendar.getInstance();
                            private final Properties settings = System.getProperties();
                            private final Integer limit = Integer.getInteger("limit");
                            private final InetAddress host;

                            public Stamps(String name) throws UnknownHostException {
                                this.host = InetAddress.getByName(name);
                            }

                            public String describe() {
                                return created + " " + today + settings + limit + host;
                            }
                        }
                        """)
                .add("Greeter", """
                        package shop;

                        import java.io.PrintStream;

                        public final class Greeter {
                            private final String name;

                            public Greeter(String name) {
                                this.name = name;
                                PrintStream out = System.err;
                                out.println("hello " + name);
                            }

                            public String name() {
                                return name;
                            }
                        }
                        """)
                .add("Canceller", """
                        package shop;

                        public final class Canceller {
                            private final Thread worker;

                            public Canceller(Thread worker) {
                                this.worker = worker;
                                worker.interrupt();
                            }

                            public Thread worker() {
                                return worker;
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.dependencies().constructorsDoNoWork().evaluate())
                .extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(7, "Banner does work in its constructor through ShopDefaults.region()"),
                        tuple(8, "Canceller does work in its constructor through Thread.interrupt()"),
                        tuple(12, "Catalog does work in its constructor through Files.readAllLines()"),
                        tuple(4, "Config does work in its static initializer through System.getenv()"),
                        tuple(11, "Greeter does work in its constructor through PrintStream.println()"),
                        tuple(11, "Journal does work in its constructor through new FileOutputStream()"),
                        tuple(9, "PriceCache does work in its constructor through Storage.load()"),
                        tuple(4, "Regions does work in its constructor through System.getenv()"),
                        tuple(5, "Reports does work in its constructor through Reports.refresh()"),
                        tuple(10, "Stamps does work in its constructor through new Date()"));
        assertThat(guard.dependencies().constructorsDoNoWork().evaluate().get(2).fix()).isEqualTo(
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
                            private final ClassLoader loader = Thread.currentThread().getContextClassLoader();

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

    @Test
    void callsThatMayRunNoEffectsAreNotWork() {
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
                .add("FileLines", """
                        package shop;

                        import java.io.BufferedReader;
                        import java.io.IOException;
                        import java.io.UncheckedIOException;
                        import java.util.Iterator;

                        public final class FileLines implements Iterator<String> {
                            private final BufferedReader reader;
                            private String line;

                            public FileLines(BufferedReader reader) {
                                this.reader = reader;
                            }

                            @Override
                            public boolean hasNext() {
                                try {
                                    line = reader.readLine();
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                                return line != null;
                            }

                            @Override
                            public String next() {
                                return line;
                            }
                        }
                        """)
                .add("Filter", """
                        package shop;

                        import java.util.Iterator;
                        import java.util.List;

                        public final class Filter {
                            private final boolean accepted;
                            private final String first;
                            private final int size;

                            public Filter(Rule rule, Iterator<String> values, List<String> names) {
                                this.accepted = rule.test("x");
                                this.first = values.hasNext() ? values.next() : "";
                                this.size = names.size();
                            }

                            public String describe() {
                                return accepted + first + size;
                            }
                        }
                        """)
                .add("Address", """
                        package shop;

                        import java.net.InetAddress;
                        import java.net.URLEncoder;
                        import java.net.UnknownHostException;
                        import java.nio.charset.StandardCharsets;
                        import java.sql.JDBCType;
                        import java.sql.Timestamp;

                        public final class Address {
                            private static final InetAddress LOOPBACK = loopback();
                            private final String query;
                            private final Timestamp created;
                            private final JDBCType type = JDBCType.valueOf("VARCHAR");

                            public Address(String query, long millis) {
                                this.query = URLEncoder.encode(query, StandardCharsets.UTF_8);
                                this.created = new Timestamp(millis);
                            }

                            private static InetAddress loopback() {
                                try {
                                    return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
                                } catch (UnknownHostException e) {
                                    throw new IllegalStateException(e);
                                }
                            }

                            public String describe() {
                                return LOOPBACK.getHostAddress() + query + created.toLocalDateTime() + type;
                            }
                        }
                        """)
                .add("Options", """
                        package shop;

                        import java.nio.file.OpenOption;
                        import java.util.concurrent.ExecutionException;
                        import java.util.concurrent.Future;

                        public final class Options {
                            private final OpenOption[] options;
                            private final String result;

                            public Options(Future<String> pending, OpenOption... options) throws ExecutionException {
                                this.options = options.clone();
                                this.result = getUninterruptibly(pending);
                            }

                            private static String getUninterruptibly(Future<String> future) throws ExecutionException {
                                boolean interrupted = false;
                                try {
                                    while (true) {
                                        try {
                                            return future.get();
                                        } catch (InterruptedException e) {
                                            interrupted = true;
                                        }
                                    }
                                } finally {
                                    if (interrupted) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            }

                            public int size() {
                                return options.length + result.length();
                            }
                        }
                        """)
                .add("CountingStream", """
                        package shop;

                        import java.io.ByteArrayOutputStream;
                        import java.io.DataOutputStream;
                        import java.io.FilterInputStream;
                        import java.io.InputStream;

                        public final class CountingStream extends FilterInputStream {
                            private final ByteArrayOutputStream copy = new ByteArrayOutputStream();
                            private final DataOutputStream data = new DataOutputStream(copy);

                            public CountingStream(InputStream in) {
                                super(in);
                            }

                            public int copied() {
                                return copy.size() + data.size();
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.dependencies().constructorsDoNoWork().evaluate()).isEmpty();
    }
}
