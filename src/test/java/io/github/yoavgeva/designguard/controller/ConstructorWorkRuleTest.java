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

    private static final String SHOP = "shop";

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
                            private final boolean cached = java.nio.file.Files.exists(java.nio.file.Path.of("regions"));
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
                                return cached + " " + size;
                            }
                        }
                        """)
                .add("Config", """
                        package shop;

                        public final class Config {
                            public static final boolean CACHED = java.nio.file.Files.exists(java.nio.file.Path.of("cache"));

                            private Config() {
                            }
                        }
                        """)
                .add("EnvDefaults", """
                        package shop;

                        public abstract class EnvDefaults {
                            public String region() {
                                return java.nio.file.Files.exists(java.nio.file.Path.of("eu")) ? "eu" : "us";
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
                .guard(SHOP);

        assertThat(guard.dependencies().constructorsDoNoWork().evaluate())
                .extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(7, "Banner does work in its constructor through ShopDefaults.region()"),
                        tuple(8, "Canceller does work in its constructor through Thread.interrupt()"),
                        tuple(12, "Catalog does work in its constructor through Files.readAllLines()"),
                        tuple(4, "Config does work in its static initializer through Files.exists()"),
                        tuple(11, "Greeter does work in its constructor through PrintStream.println()"),
                        tuple(11, "Journal does work in its constructor through new FileOutputStream()"),
                        tuple(9, "PriceCache does work in its constructor through Storage.load()"),
                        tuple(4, "Regions does work in its constructor through Files.exists()"),
                        tuple(5, "Reports does work in its constructor through Reports.refresh()"),
                        tuple(17, "Stamps does work in its constructor through InetAddress.getByName()"));
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
                .guard(SHOP);

        assertThat(guard.dependencies().constructorsDoNoWork().evaluate()).isEmpty();
    }

    @Test
    void creatingALibraryClientIsNotWorkButCallingOneIs() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("WebClient", """
                        package org.springframework.web.reactive.function.client;

                        public interface WebClient {
                            String get(String uri);

                            static WebClient create(String baseUrl) {
                                return uri -> baseUrl + uri;
                            }

                            static Builder builder() {
                                return WebClient::create;
                            }

                            interface Builder {
                                WebClient build(String baseUrl);
                            }
                        }
                        """)
                .add("RestOperations", "package org.springframework.web.client;\n\npublic interface RestOperations {\n"
                        + "    String getForObject(String url);\n}\n")
                .add("RestTemplate", """
                        package org.springframework.web.client;

                        public class RestTemplate implements RestOperations {
                            @Override
                            public String getForObject(String url) {
                                return url;
                            }

                            public void setErrorHandler(Object handler) {
                            }

                            public RestTemplate withRoot(String root) {
                                return this;
                            }
                        }
                        """)
                .add("Gateway", """
                        package shop;

                        import org.springframework.web.client.RestTemplate;
                        import org.springframework.web.reactive.function.client.WebClient;

                        public final class Gateway {
                            private final WebClient web;
                            private final WebClient other;
                            private final RestTemplate rest;

                            public Gateway(String baseUrl) {
                                this.web = WebClient.builder().build(baseUrl);
                                this.other = WebClient.create(baseUrl);
                                this.rest = new RestTemplate().withRoot(baseUrl);
                                this.rest.setErrorHandler(baseUrl);
                            }

                            public String fetch(String uri) {
                                return web.get(uri) + other.get(uri) + rest.getForObject(uri);
                            }
                        }
                        """)
                .add("Warmup", """
                        package shop;

                        import org.springframework.web.client.RestTemplate;

                        public final class Warmup {
                            private final String status;

                            public Warmup(RestTemplate rest) {
                                this.status = rest.getForObject("/health");
                            }

                            public String status() {
                                return status;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().constructorsDoNoWork().evaluate()).extracting(Violation::message)
                .containsExactly("Warmup does work in its constructor through RestTemplate.getForObject()");
    }

    @Test
    void adaptersAndEnumsOfIoPackagesAreNotWork() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Copier", """
                        package shop;

                        import java.io.IOException;
                        import java.nio.file.FileVisitResult;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.nio.file.SimpleFileVisitor;
                        import java.nio.file.StandardOpenOption;
                        import java.nio.file.attribute.BasicFileAttributes;
                        import java.util.Set;

                        public final class Copier {
                            private final Set<StandardOpenOption> options;
                            private final SimpleFileVisitor<Path> visitor;

                            public Copier() {
                                this.options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                                this.visitor = new SimpleFileVisitor<>() {
                                    @Override
                                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                                        return FileVisitResult.CONTINUE;
                                    }
                                };
                            }

                            public void copy(Path from) throws IOException {
                                Files.walkFileTree(from, visitor);
                            }

                            public Set<StandardOpenOption> options() {
                                return options;
                            }
                        }
                        """)
                .add("OpenOptions", """
                        package shop;

                        import java.nio.file.StandardOpenOption;
                        import java.util.EnumSet;
                        import java.util.Set;

                        public final class OpenOptions {
                            private Set<StandardOpenOption> chosen = EnumSet.noneOf(StandardOpenOption.class);

                            public OpenOptions with(StandardOpenOption option) {
                                chosen = EnumSet.copyOf(chosen);
                                chosen.add(option);
                                return this;
                            }
                        }
                        """)
                .add("Cookies", """
                        package shop;

                        import java.net.CookieManager;
                        import java.net.HttpCookie;
                        import java.net.URI;

                        public final class Cookies {
                            private final CookieManager manager = new CookieManager();

                            public Cookies(String name, String value) {
                                manager.getCookieStore().add(URI.create("https://shop.example"), new HttpCookie(name, value));
                            }

                            public int count() {
                                return manager.getCookieStore().getCookies().size();
                            }
                        }
                        """)
                .add("Reaper", """
                        package shop;

                        final class Reaper extends Thread {
                            Reaper() {
                                super("reaper");
                                setPriority(Thread.MAX_PRIORITY);
                                setDaemon(true);
                            }
                        }
                        """)
                .add("NoPipe", """
                        package shop;

                        public final class NoPipe extends java.nio.channels.Pipe {
                            private final String name;

                            public NoPipe(String name) {
                                this.name = name;
                            }

                            @Override
                            public SourceChannel source() {
                                throw new UnsupportedOperationException(name + " has no source");
                            }

                            @Override
                            public SinkChannel sink() {
                                throw new UnsupportedOperationException(name + " has no sink");
                            }
                        }
                        """)
                .add("Link", """
                        package shop;

                        public final class Link {
                            private final java.net.URL url;

                            public Link(java.net.URLConnection connection) {
                                connection.setConnectTimeout(1000);
                                this.url = connection.getURL();
                            }

                            public java.net.URL url() {
                                return url;
                            }
                        }
                        """)
                .add("PasswordPrompt", """
                        package shop;

                        import java.net.Authenticator;
                        import java.net.PasswordAuthentication;

                        public final class PasswordPrompt extends Authenticator {
                            private final String user;

                            public PasswordPrompt(String user) {
                                this.user = user;
                            }

                            @Override
                            protected PasswordAuthentication getPasswordAuthentication() {
                                return new PasswordAuthentication(user, new char[0]);
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().constructorsDoNoWork().evaluate()).isEmpty();
        assertThat(guard.types().servicesAreStateless().evaluate()).isEmpty();
    }

    @Test
    void theClockRandomnessAndConfigurationAreNotWork() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Tokens", """
                        package shop;

                        import java.security.SecureRandom;
                        import java.time.Clock;
                        import java.util.Random;
                        import java.util.concurrent.ThreadLocalRandom;

                        public final class Tokens {
                            private final Clock clock = Clock.systemUTC();
                            private final Clock zoned = Clock.systemDefaultZone();
                            private final SecureRandom secure = new SecureRandom();
                            private final Random seeded = new Random(42);
                            private final Random local = ThreadLocalRandom.current();

                            public String next() {
                                return clock.instant() + "" + zoned.millis() + secure.nextInt() + seeded.nextInt() + local.nextInt();
                            }
                        }
                        """)
                .add("Session", """
                        package shop;

                        import java.time.Clock;
                        import java.time.Instant;

                        public final class Session {
                            private final Instant started;

                            public Session(Clock clock) {
                                this.started = Instant.now(clock);
                            }

                            public Instant started() {
                                return started;
                            }
                        }
                        """)
                .add("Entry", """
                        package shop;

                        import java.time.Instant;

                        public final class Entry {
                            private static final Instant STARTED = Instant.now();
                            private static final boolean NATIVE = "runtime".equals(System.getProperty("image"));
                            private static final boolean ANSI = System.console() != null && !"dumb".equals(System.getenv("TERM"));
                            private final long stamp = System.currentTimeMillis();
                            private final String id = java.util.UUID.randomUUID().toString();
                            private static final int SEGMENTS = Runtime.getRuntime().availableProcessors() * 4;
                            private static final long HEAP = Runtime.getRuntime().maxMemory();

                            public String describe() {
                                return STARTED + " " + NATIVE + ANSI + stamp + id + SEGMENTS + HEAP;
                            }
                        }
                        """)
                .guard(SHOP);

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
                            private final String client;

                            public Address(String query, long millis, java.net.InetSocketAddress remote) {
                                this.query = URLEncoder.encode(query, StandardCharsets.UTF_8);
                                this.created = new Timestamp(millis);
                                this.client = remote.isUnresolved() ? remote.getHostString()
                                        : remote.getAddress().getHostAddress() + ":" + remote.getPort();
                            }

                            private static InetAddress loopback() {
                                try {
                                    return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
                                } catch (UnknownHostException e) {
                                    throw new IllegalStateException(e);
                                }
                            }

                            public String describe() {
                                return LOOPBACK.getHostAddress() + query + created.toLocalDateTime() + type + client;
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
                .guard(SHOP);

        assertThat(guard.dependencies().constructorsDoNoWork().evaluate()).isEmpty();
    }

    @Test
    void reportingACaughtErrorOnStandardErrorIsNotWork() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Version", """
                        package shop;

                        public final class Version {
                            private static final boolean MODERN = isModern();

                            private Version() {
                            }

                            static boolean isModern() {
                                try {
                                    return Integer.parseInt(System.getProperty("java.specification.version")) >= 17;
                                } catch (NumberFormatException e) {
                                    if (e.getMessage() != null) {
                                        System.err.println("can't tell the Java version: " + e.getMessage());
                                    }
                                    return false;
                                }
                            }

                            public static boolean modern() {
                                return MODERN;
                            }
                        }
                        """)
                .add("Banner", """
                        package shop;

                        public final class Banner {
                            private static final int WIDTH = width();

                            private Banner() {
                            }

                            static int width() {
                                int width;
                                try {
                                    width = Integer.parseInt(System.getenv("COLUMNS"));
                                } catch (NumberFormatException e) {
                                    System.out.println("no width: " + e.getMessage());
                                    width = 80;
                                }
                                System.err.println("width " + width);
                                return width;
                            }

                            public static int columns() {
                                return WIDTH;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.dependencies().constructorsDoNoWork().evaluate()).extracting(Violation::message)
                .containsExactly("Banner does work in its static initializer through Banner.width()");
    }
}
