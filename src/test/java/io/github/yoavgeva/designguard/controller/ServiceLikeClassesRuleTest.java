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

/**
 * Which classes count as service-like, seen through {@code servicesAreStateless}, which reports service-like classes
 * with state. Runs once for each Java version's class files: see {@link CompiledProject#releases()}.
 */
@ParameterizedClass(name = "class files for Java {0}")
@MethodSource("io.github.yoavgeva.designguard.CompiledProject#releases")
class ServiceLikeClassesRuleTest {

    private static final String SHOP = "shop";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void collaboratorsACallIsGivenAreTheCallersButStaticOnesAreTheClasss() {
        DesignGuard guard = withDiskStorage()
                .add("Pricing", """
                        package shop;

                        public final class Pricing {
                            private int calls;

                            public int count(Storage storage) {
                                calls++;
                                return storage.size() + calls;
                            }
                        }
                        """)
                .add("Ticks", """
                        package shop;

                        public final class Ticks {
                            private static final Storage STORAGE = new DiskStorage();

                            private Ticks() {
                            }

                            public static int count() {
                                return STORAGE.size();
                            }
                        }
                        """)
                .add("Counter", """
                        package shop;

                        public final class Counter {
                            private int calls;

                            public int count() {
                                calls++;
                                return Ticks.count() + calls;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Counter is service-like, but its field 'calls' isn't final");
    }

    @Test
    void subclassesOfServicesAreServicesButSiblingsOfOneAreNot() {
        DesignGuard guard = withDiskStorage()
                .add("Synchronization", """
                        package shop;

                        public abstract class Synchronization {
                            protected final Storage storage;

                            protected Synchronization(Storage storage) {
                                this.storage = storage;
                            }
                        }
                        """)
                .add("CacheSynchronization", """
                        package shop;

                        public class CacheSynchronization extends Synchronization {
                            private int flushes;

                            public CacheSynchronization(Storage storage) {
                                super(storage);
                            }

                            public void flush() {
                                flushes++;
                            }
                        }
                        """)
                .add("Saver", """
                        package shop;

                        public class Saver {
                            public int save() {
                                return System.getenv("SAVES").length();
                            }
                        }
                        """)
                .add("QuickSaver", """
                        package shop;

                        public final class QuickSaver extends Saver {
                            private int saves;

                            public int saves() {
                                return ++saves;
                            }
                        }
                        """)
                .add("Shape", """
                        package shop;

                        public abstract class Shape {
                            public abstract int area();

                            public int twice() {
                                return area() * 2;
                            }
                        }
                        """)
                .add("EnvShape", """
                        package shop;

                        public final class EnvShape extends Shape {
                            @Override
                            public int area() {
                                return System.getenv("AREA").length();
                            }
                        }
                        """)
                .add("Square", """
                        package shop;

                        public final class Square extends Shape {
                            private int side;

                            @Override
                            public int area() {
                                side++;
                                return side * side;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "CacheSynchronization is service-like, but its field 'flushes' isn't final",
                "QuickSaver is service-like, but its field 'saves' isn't final");
    }

    @Test
    void functionalInterfacesPassBehaviourAsDataLikeJavaUtilFunction() {
        DesignGuard guard = withDiskStorage()
                .add("Check", "package shop;\n\n@FunctionalInterface\npublic interface Check {\n"
                        + "    boolean test(String value);\n}\n")
                .add("EnvCheck", """
                        package shop;

                        public final class EnvCheck implements Check {
                            private int calls;

                            @Override
                            public boolean test(String value) {
                                calls++;
                                return System.getenv(value) != null && calls > 0;
                            }
                        }
                        """)
                .add("Matcher", """
                        package shop;

                        public final class Matcher {
                            private final Check check;
                            private int matches;

                            public Matcher(Check check) {
                                this.check = check;
                            }

                            public boolean matches(String value) {
                                if (check.test(value)) {
                                    matches++;
                                }
                                return matches > 0;
                            }
                        }
                        """)
                .add("Cache", """
                        package shop;

                        public final class Cache {
                            private final Storage storage;
                            private int hits;

                            public Cache(Storage storage) {
                                this.storage = storage;
                            }

                            public int size() {
                                hits++;
                                return storage.size() + hits;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Cache is service-like, but its field 'hits' isn't final",
                "EnvCheck is service-like, but its field 'calls' isn't final");
    }

    @Test
    void repositoriesAndClientsOfLibrariesTouchTheOutsideWorld() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Repository", "package org.springframework.data.repository;\n\n"
                        + "public interface Repository<T, ID> {\n}\n")
                .add("CrudRepository", "package org.springframework.data.repository;\n\n"
                        + "public interface CrudRepository<T, ID> extends Repository<T, ID> {\n    T save(T entity);\n}\n")
                .add("JdbcOperations", "package org.springframework.jdbc.core;\n\npublic interface JdbcOperations {\n"
                        + "    int update(String sql);\n}\n")
                .add("Mapper", "package org.apache.ibatis.annotations;\n\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\npublic @interface Mapper {\n}\n")
                .add("Mapper", "package org.mapstruct;\n\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\npublic @interface Mapper {\n}\n")
                .add("ArticleRepository", "package shop;\n\npublic interface ArticleRepository "
                        + "extends org.springframework.data.repository.CrudRepository<String, Long> {\n"
                        + "    String findBySlug(String slug);\n}\n")
                .add("OrderMapper", "package shop;\n\n@org.apache.ibatis.annotations.Mapper\npublic interface OrderMapper {\n"
                        + "    String find(long id);\n}\n")
                .add("OrderDtoMapper", "package shop;\n\n@org.mapstruct.Mapper\npublic interface OrderDtoMapper {\n"
                        + "    String toDto(String order);\n}\n")
                .add("UpperDtoMapper", """
                        package shop;

                        public final class UpperDtoMapper implements OrderDtoMapper {
                            @Override
                            public String toDto(String order) {
                                return order.toUpperCase(java.util.Locale.ROOT);
                            }
                        }
                        """)
                .add("Articles", """
                        package shop;

                        public final class Articles {
                            private final ArticleRepository repository;
                            private int lookups;

                            public Articles(ArticleRepository repository) {
                                this.repository = repository;
                            }

                            public String find(String slug) {
                                lookups++;
                                return repository.findBySlug(slug);
                            }
                        }
                        """)
                .add("Archive", """
                        package shop;

                        public final class Archive {
                            private int archived;

                            public int archive(org.springframework.jdbc.core.JdbcOperations jdbc) {
                                archived += jdbc.update("update article set archived = true");
                                return archived;
                            }
                        }
                        """)
                .add("Orders", """
                        package shop;

                        public final class Orders {
                            private final OrderMapper mapper;
                            private String last;

                            public Orders(OrderMapper mapper) {
                                this.mapper = mapper;
                            }

                            public String find(long id) {
                                last = mapper.find(id);
                                return last;
                            }
                        }
                        """)
                .add("Converter", """
                        package shop;

                        public final class Converter {
                            private final OrderDtoMapper mapper;
                            private String last;

                            public Converter(OrderDtoMapper mapper) {
                                this.mapper = mapper;
                            }

                            public String convert(String order) {
                                last = mapper.toDto(order);
                                return last;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Archive is service-like, but its field 'archived' isn't final",
                "Articles is service-like, but its field 'lookups' isn't final",
                "Orders is service-like, but its field 'last' isn't final");
    }

    @Test
    void aLibraryTypeTheProjectImplementsIsJudgedByThatCode() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("JdbcOperations", "package org.springframework.jdbc.core;\n\npublic interface JdbcOperations {\n"
                        + "    int update(String sql);\n}\n")
                .add("EntityManager", "package jakarta.persistence;\n\npublic interface EntityManager {\n"
                        + "    void persist(Object entity);\n}\n")
                .add("CountingJdbc", """
                        package org.springframework.jdbc.core;

                        public final class CountingJdbc implements JdbcOperations {
                            @Override
                            public int update(String sql) {
                                return sql.length();
                            }
                        }
                        """)
                .add("Batch", """
                        package org.springframework.jdbc.core;

                        public final class Batch {
                            private final JdbcOperations jdbc;
                            private int rows;

                            public Batch(JdbcOperations jdbc) {
                                this.jdbc = jdbc;
                            }

                            public int run(String sql) {
                                rows += jdbc.update(sql);
                                return rows;
                            }
                        }
                        """)
                .add("MemorySession", """
                        package org.springframework.jdbc.memory;

                        import jakarta.persistence.EntityManager;
                        import java.util.ArrayList;
                        import java.util.List;

                        public final class MemorySession implements EntityManager {
                            private final List<Object> entities = new ArrayList<>();

                            @Override
                            public void persist(Object entity) {
                                entities.add(entity);
                            }
                        }
                        """)
                .add("Saver", """
                        package org.springframework.jdbc.memory;

                        import jakarta.persistence.EntityManager;

                        public final class Saver {
                            private final EntityManager session;
                            private int saved;

                            public Saver(EntityManager session) {
                                this.session = session;
                            }

                            public void save(Object entity) {
                                saved++;
                                session.persist(entity);
                            }
                        }
                        """)
                .guard("org.springframework.jdbc");

        assertThat(guard.types().servicesAreStateless().evaluate()).isEmpty();
    }

    @Test
    void anAbstractionIsServiceLikeWhenCallingItCanReachEffects() {
        DesignGuard guard = withDiskStorage()
                .add("Model", "package shop;\n\npublic interface Model {\n    String name();\n\n    String kind();\n}\n")
                .add("Post", """
                        package shop;

                        public final class Post implements Model {
                            @Override
                            public String name() {
                                return "post";
                            }

                            @Override
                            public String kind() {
                                return "content";
                            }
                        }
                        """)
                .add("Subscription", """
                        package shop;

                        public final class Subscription implements Model {
                            @Override
                            public String name() {
                                return "subscription";
                            }

                            @Override
                            public String kind() {
                                return "notification";
                            }

                            public String unsubscribeToken() {
                                return java.util.UUID.randomUUID().toString();
                            }
                        }
                        """)
                .add("Listing", """
                        package shop;

                        public final class Listing {
                            private Model subject;

                            public void show(Model model) {
                                subject = model;
                            }
                        }
                        """)
                .add("Cache", """
                        package shop;

                        public final class Cache {
                            private final Storage storage;
                            private int hits;

                            public Cache(Storage storage) {
                                this.storage = storage;
                            }

                            public int size() {
                                hits++;
                                return storage.size() + hits;
                            }
                        }
                        """)
                .add("ConfigFetcher", "package shop;\n\npublic interface ConfigFetcher {\n    String fetch();\n}\n")
                .add("CachedConfigFetcher", """
                        package shop;

                        import java.util.function.Supplier;

                        public final class CachedConfigFetcher implements ConfigFetcher {
                            private final Supplier<String> config = () -> System.getenv("CONFIG");

                            @Override
                            public String fetch() {
                                return config.get();
                            }
                        }
                        """)
                .add("Settings", """
                        package shop;

                        public final class Settings {
                            private final ConfigFetcher fetcher;
                            private String last;

                            public Settings(ConfigFetcher fetcher) {
                                this.fetcher = fetcher;
                            }

                            public String current() {
                                last = fetcher.fetch();
                                return last;
                            }
                        }
                        """)
                .add("Context", "package shop;\n\npublic interface Context {\n    Storage storage();\n}\n")
                .add("RequestContext", """
                        package shop;

                        public final class RequestContext implements Context {
                            private final Storage storage;

                            public RequestContext(Storage storage) {
                                this.storage = storage;
                            }

                            @Override
                            public Storage storage() {
                                return storage;
                            }
                        }
                        """)
                .add("Shape", "package shop;\n\npublic interface Shape {\n    int area();\n\n    int sides();\n}\n")
                .add("Square", "package shop;\n\npublic final class Square implements Shape {\n"
                        + "    public int area() {\n        return 4;\n    }\n\n    public int sides() {\n        return 4;\n    }\n}\n")
                .add("Crypto", "package shop;\n\npublic interface Crypto {\n    byte[] decrypt(byte[] message);\n\n"
                        + "    byte[] publicKey();\n}\n")
                .add("RsaKeys", """
                        package shop;

                        public final class RsaKeys implements Crypto {
                            private byte[] key = new byte[0];

                            public void afterPropertiesSet() throws java.io.IOException {
                                key = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("keys/private.pem"));
                            }

                            @Override
                            public byte[] decrypt(byte[] message) {
                                return message.length > key.length ? message : key;
                            }

                            @Override
                            public byte[] publicKey() {
                                return key.clone();
                            }
                        }
                        """)
                .addTest("ShopTest", """
                        package shop;

                        class ShopTest {
                            @org.junit.jupiter.api.Test
                            void mocks() {
                                Context context = org.mockito.Mockito.mock(Context.class);
                                Crypto crypto = org.mockito.Mockito.mock(Crypto.class);
                                Model model = org.mockito.Mockito.mock(Model.class);
                                Shape shape = org.mockito.Mockito.mock(Shape.class);
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Cache is service-like, but its field 'hits' isn't final",
                "RsaKeys is service-like, but its field 'key' isn't final",
                "Settings is service-like, but its field 'last' isn't final");
        assertThat(guard.tests().testsDontMockValues().evaluate()).extracting(Violation::message).containsExactly(
                "ShopTest mocks Shape, which is a value");
    }

    @Test
    void persistentClassesAreDataEvenWhenTheyReadTheClock() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Entity", "package shop;\n\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface Entity {\n}\n")
                .add("Visit", """
                        package shop;

                        import java.time.Instant;
                        import java.time.LocalDate;

                        @Entity
                        public class Visit {
                            private LocalDate date;
                            private Instant created;

                            public Visit() {
                                this.date = LocalDate.now();
                            }

                            public void stamp() {
                                created = Instant.now();
                            }
                        }
                        """)
                .add("Pet", """
                        package shop;

                        import java.util.LinkedHashSet;
                        import java.util.Set;

                        @Entity
                        public class Pet {
                            private String name;
                            private final Set<Visit> visits = new LinkedHashSet<>();

                            public void addVisit(Visit visit) {
                                visits.add(visit);
                            }
                        }
                        """)
                .add("Agenda", """
                        package shop;

                        import java.util.ArrayList;
                        import java.util.List;

                        public final class Agenda {
                            private final List<Visit> visits = new ArrayList<>();
                            private int booked;

                            public void book(Visit visit) {
                                visits.add(visit);
                                booked++;
                            }
                        }
                        """)
                .add("Ticker", """
                        package shop;

                        public final class Ticker {
                            private final java.time.Clock clock = java.time.Clock.systemUTC();
                            private long last;

                            public void tick() {
                                last = clock.millis();
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Ticker is service-like, but its field 'last' isn't final");
    }

    @Test
    void dataThatOnlyReadsTheClockOrRandomnessIsNoSharedService() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Ref", "package shop;\n\npublic record Ref(String kind, String name) {\n}\n")
                .add("CommentRequest", """
                        package shop;

                        import java.util.List;
                        import java.util.UUID;

                        public class CommentRequest {
                            private String raw;
                            private Ref subject;
                            private List<String> tags;

                            public String toComment() {
                                return UUID.randomUUID() + raw + subject + tags;
                            }
                        }
                        """)
                .add("CommentDraft", """
                        package shop;

                        public class CommentDraft {
                            private CommentRequest request;

                            public void edit(CommentRequest edited) {
                                request = edited;
                            }
                        }
                        """)
                .add("Stopwatch", """
                        package shop;

                        public final class Stopwatch {
                            private long started;

                            public void start() {
                                started = System.nanoTime();
                            }
                        }
                        """)
                .add("Tokens", """
                        package shop;

                        public final class Tokens {
                            public String next() {
                                return java.util.UUID.randomUUID().toString();
                            }
                        }
                        """)
                .add("Uploader", """
                        package shop;

                        public final class Uploader {
                            private String last;

                            public void upload(byte[] bytes) throws java.io.IOException {
                                last = java.util.UUID.randomUUID().toString();
                                java.nio.file.Files.write(java.nio.file.Path.of(last), bytes);
                            }
                        }
                        """)
                .add("Comments", """
                        package shop;

                        public final class Comments {
                            public String draft(CommentRequest request) {
                                return new CommentRequest().toComment() + new Stopwatch() + new Uploader() + request;
                            }
                        }
                        """)
                .addTest("CommentsTest", """
                        package shop;

                        class CommentsTest {
                            @org.junit.jupiter.api.Test
                            void drafts() {
                                CommentRequest request = org.mockito.Mockito.mock(CommentRequest.class);
                                Stopwatch stopwatch = org.mockito.Mockito.mock(Stopwatch.class);
                                Tokens tokens = org.mockito.Mockito.mock(Tokens.class);
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Uploader is service-like, but its field 'last' isn't final");
        assertThat(guard.dependencies().injectServices().evaluate()).extracting(Violation::message).containsExactly(
                "Comments creates service-like Uploader with new");
        assertThat(guard.tests().testsDontMockValues().evaluate()).isEmpty();
    }

    /** A project with a storage interface whose one implementation reads the environment, so both are service-like. */
    private CompiledProject withDiskStorage() {
        return new CompiledProject(directory).release(release)
                .add("Storage", "package shop;\n\npublic interface Storage {\n    int size();\n}\n")
                .add("DiskStorage", """
                        package shop;

                        public final class DiskStorage implements Storage {
                            @Override
                            public int size() {
                                return System.getenv("SIZE").length();
                            }
                        }
                        """);
    }
}
