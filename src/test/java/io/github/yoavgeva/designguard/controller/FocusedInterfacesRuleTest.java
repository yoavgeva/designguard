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
class FocusedInterfacesRuleTest {

    private static final String SHOP = "shop";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void interfacesAreNoWiderThanTheirClientsNeed() {
        String store = """
                package shop;

                public interface Store {
                    String load(String id);

                    void save(String id, String value);

                    void delete(String id);

                    java.util.List<String> all();

                    int count();

                    boolean exists(String id);

                    void rename(String from, String to);

                    void copy(String from, String to);

                    void lock(String id);

                    void unlock(String id);
                }
                """;
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Store", store)
                .add("Reader", "package shop;\n\npublic class Reader {\n    public String read(Store store) {\n"
                        + "        return store.load(\"a\");\n    }\n}\n")
                .add("Writer", "package shop;\n\npublic class Writer {\n    public void write(Store store) {\n"
                        + "        store.save(\"a\", \"b\");\n        store.delete(\"c\");\n    }\n}\n")
                .add("Lister", "package shop;\n\npublic class Lister {\n    public int count(Store store) {\n"
                        + "        return store.all().size() + store.count();\n    }\n}\n")
                .add("Catalog", store.replace("Store", "Catalog"))
                .add("Browser", "package shop;\n\npublic class Browser {\n    public String browse(Catalog catalog) {\n"
                        + "        catalog.save(\"a\", \"b\");\n        catalog.delete(\"a\");\n"
                        + "        return catalog.load(\"a\") + catalog.all() + catalog.count() + catalog.exists(\"a\");\n"
                        + "    }\n}\n")
                .add("Viewer", "package shop;\n\npublic class Viewer {\n    public String view(Catalog catalog) {\n"
                        + "        return catalog.load(\"a\");\n    }\n}\n")
                .add("Tally", store.replace("Store", "Tally"))
                .add("HashTally", """
                        package shop;

                        import java.util.HashMap;
                        import java.util.List;
                        import java.util.Map;

                        public final class HashTally implements Tally {
                            private final Map<String, String> values = new HashMap<>();

                            public String load(String id) {
                                return values.get(id);
                            }

                            public void save(String id, String value) {
                                values.put(id, value);
                            }

                            public void delete(String id) {
                                values.remove(id);
                            }

                            public List<String> all() {
                                return List.copyOf(values.values());
                            }

                            public int count() {
                                return values.size();
                            }

                            public boolean exists(String id) {
                                return values.containsKey(id);
                            }

                            public void rename(String from, String to) {
                                values.put(to, values.remove(from));
                            }

                            public void copy(String from, String to) {
                                values.put(to, values.get(from));
                            }

                            public void lock(String id) {
                            }

                            public void unlock(String id) {
                            }
                        }
                        """)
                .add("Adder", "package shop;\n\npublic class Adder {\n    public void add(Tally tally) {\n"
                        + "        tally.save(\"a\", \"b\");\n    }\n}\n")
                .add("Counter", "package shop;\n\npublic class Counter {\n    public int count(Tally tally) {\n"
                        + "        return tally.all().size();\n    }\n}\n")
                .add("DiskStore", """
                        package shop;

                        import java.io.IOException;
                        import java.io.UncheckedIOException;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.List;

                        public abstract class DiskStore implements Store {
                            public String load(String id) {
                                try {
                                    return Files.readString(Path.of(id));
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }
                        }
                        """)
                .add("Port", "package shop;\n\npublic interface Port {\n    void open(String id);\n\n"
                        + "    void close(String id);\n\n    String read(String id);\n\n    void write(String id, String data);\n}\n")
                .add("Opener", "package shop;\n\npublic class Opener {\n    public void open(Port port) {\n"
                        + "        port.open(\"a\");\n    }\n}\n")
                .add("Closer", "package shop;\n\npublic class Closer {\n    public void close(Port port) {\n"
                        + "        port.close(\"a\");\n    }\n}\n")
                .add("Lifecycle", """
                        package shop;

                        public interface Lifecycle {
                            void start();

                            void stop();

                            boolean isRunning();

                            String state();

                            void awaitRunning();

                            void awaitRunning(long millis);

                            void awaitTerminated();

                            void awaitTerminated(long millis);

                            Throwable failureCause();

                            void addListener(Runnable listener);

                            void restart();

                            void pause();
                        }
                        """)
                .add("Manager", "package shop;\n\npublic class Manager {\n    public String manage(Lifecycle service) {\n"
                        + "        service.start();\n        service.stop();\n        service.addListener(() -> { });\n"
                        + "        return service.isRunning() + service.state() + service.failureCause();\n    }\n}\n")
                .add("Watcher", "package shop;\n\npublic class Watcher {\n    public void watch(Lifecycle service) {\n"
                        + "        service.awaitRunning();\n        service.awaitTerminated();\n    }\n}\n")
                .add("Request", """
                        package shop;

                        public interface Request {
                            Request url(String url);

                            Request timeout(int millis);

                            Request header(String name, String value);

                            Request cookie(String name, String value);

                            Request data(String key, String value);

                            Request method(String method);

                            Request followRedirects(boolean follow);

                            Request userAgent(String agent);

                            Request proxy(String host, int port);

                            String execute();
                        }
                        """)
                .add("Fetcher", "package shop;\n\npublic class Fetcher {\n    public String fetch(Request request) {\n"
                        + "        return request.url(\"https://a\").timeout(1).execute();\n    }\n}\n")
                .add("Poster", "package shop;\n\npublic class Poster {\n    public String post(Request request) {\n"
                        + "        return request.data(\"a\", \"b\").method(\"POST\").execute();\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.types().interfacesAreFocused().evaluate()).extracting(Violation::message, Violation::fix)
                .containsExactly(tuple("Store has 10 methods, but each of its 3 clients uses at most 2 of them",
                        "split it into smaller interfaces, one for each role its clients need, and let implementations"
                                + " implement several"));
    }

    @Test
    void collectionsAreValuesWhateverElseImplementsTheirIteratorsAndInheritedMethodsAreNoWidth() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Cursor", "package shop.bags;\n\npublic abstract class Cursor<E> implements java.util.Iterator<E> {\n}\n")
                .add("FileCursor", """
                        package shop.bags;

                        import java.nio.file.Files;
                        import java.nio.file.Path;

                        public final class FileCursor extends Cursor<String> {
                            private final Path path;

                            public FileCursor(Path path) {
                                this.path = path;
                            }

                            @Override
                            public boolean hasNext() {
                                return Files.exists(path);
                            }

                            @Override
                            public String next() {
                                return path.toString();
                            }
                        }
                        """)
                .add("ItemCursor", """
                        package shop.bags;

                        import java.util.List;

                        final class ItemCursor<E> extends Cursor<E> {
                            private final List<E> items;
                            private int index;

                            ItemCursor(List<E> items) {
                                this.items = items;
                            }

                            @Override
                            public boolean hasNext() {
                                return index < items.size();
                            }

                            @Override
                            public E next() {
                                return items.get(index++);
                            }
                        }
                        """)
                .add("Bag", "package shop.bags;\n\npublic interface Bag<E> {\n    int count(E element);\n\n"
                        + "    void add(E element);\n\n    void remove(E element);\n\n    Cursor<E> cursor();\n\n"
                        + "    boolean isEmpty();\n\n    int size();\n\n    void clear();\n\n    boolean contains(E element);\n\n"
                        + "    E first();\n\n    E last();\n}\n")
                .add("ListBag", """
                        package shop.bags;

                        import java.util.ArrayList;
                        import java.util.List;

                        public final class ListBag<E> implements Bag<E> {
                            private final List<E> items = new ArrayList<>();

                            public int count(E element) {
                                return (int) items.stream().filter(element::equals).count();
                            }

                            public void add(E element) {
                                items.add(element);
                            }

                            public void remove(E element) {
                                items.remove(element);
                            }

                            public Cursor<E> cursor() {
                                return new ItemCursor<>(items);
                            }

                            public boolean isEmpty() {
                                return !cursor().hasNext();
                            }

                            public int size() {
                                return items.size();
                            }

                            public void clear() {
                                items.clear();
                            }

                            public boolean contains(E element) {
                                return items.contains(element);
                            }

                            public E first() {
                                return items.get(0);
                            }

                            public E last() {
                                return items.get(items.size() - 1);
                            }
                        }
                        """)
                .add("Taker", "package shop.bags;\n\npublic class Taker {\n    public int take(Bag<String> bag) {\n"
                        + "        return bag.isEmpty() ? 0 : bag.count(\"a\");\n    }\n}\n")
                .add("Filler", "package shop.bags;\n\npublic class Filler {\n    public void fill(Bag<String> bag) {\n"
                        + "        bag.add(\"a\");\n        bag.remove(\"b\");\n    }\n}\n")
                .add("Jobs", """
                        package shop.jobs;

                        import java.util.Collection;
                        import java.util.List;
                        import java.util.concurrent.Callable;
                        import java.util.concurrent.ExecutionException;
                        import java.util.concurrent.ExecutorService;
                        import java.util.concurrent.Future;
                        import java.util.concurrent.TimeUnit;

                        public interface Jobs extends ExecutorService {
                            @Override
                            void execute(Runnable task);

                            @Override
                            Future<?> submit(Runnable task);

                            @Override
                            void shutdown();

                            @Override
                            List<Runnable> shutdownNow();

                            @Override
                            boolean isShutdown();

                            @Override
                            boolean isTerminated();

                            @Override
                            boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

                            @Override
                            <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException;

                            @Override
                            <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                                    throws InterruptedException, ExecutionException;

                            @Override
                            String toString();
                        }
                        """)
                .add("Scheduler", "package shop.jobs;\n\npublic class Scheduler {\n    public void schedule(Jobs jobs) {\n"
                        + "        jobs.submit(() -> { });\n    }\n}\n")
                .add("Closer", "package shop.jobs;\n\npublic class Closer {\n    public void close(Jobs jobs) {\n"
                        + "        jobs.shutdown();\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.types().interfacesAreFocused().evaluate()).isEmpty();
    }
}
