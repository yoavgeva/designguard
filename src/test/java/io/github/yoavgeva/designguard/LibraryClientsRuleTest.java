package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which library clients make classes service-like, seen through {@code servicesAreStateless}, which reports
 * service-like classes with state. Runs once for each Java version's class files: see {@link CompiledProject#releases()}.
 */
@ParameterizedClass(name = "class files for Java {0}")
@MethodSource("io.github.yoavgeva.designguard.CompiledProject#releases")
class LibraryClientsRuleTest {

    private static final String SHOP = "shop";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

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
    void quarkusRepositoriesActiveRecordsAndRestClientsTouchTheOutsideWorld() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("PanacheRepositoryBase", """
                        package io.quarkus.hibernate.orm.panache;

                        public interface PanacheRepositoryBase<E, I> {
                            default java.util.List<E> listAll() {
                                throw new UnsupportedOperationException("enhanced at build time");
                            }
                        }
                        """)
                .add("PanacheRepository", "package io.quarkus.hibernate.orm.panache;\n\n"
                        + "public interface PanacheRepository<E> extends PanacheRepositoryBase<E, Long> {\n}\n")
                .add("PanacheEntityBase", """
                        package io.quarkus.hibernate.orm.panache;

                        public abstract class PanacheEntityBase {
                            public static <T> java.util.List<T> listAll() {
                                throw new UnsupportedOperationException("enhanced at build time");
                            }

                            public void persist() {
                                throw new UnsupportedOperationException("enhanced at build time");
                            }
                        }
                        """)
                .add("RegisterRestClient", "package org.eclipse.microprofile.rest.client.inject;\n\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface RegisterRestClient {\n}\n")
                .add("Fruit", """
                        package shop;

                        public class Fruit extends io.quarkus.hibernate.orm.panache.PanacheEntityBase {
                            public String name;

                            public static java.util.List<Fruit> named(String name) {
                                return Fruit.<Fruit>listAll().stream().filter(fruit -> fruit.name.equals(name)).toList();
                            }

                            public String label() {
                                return name.toUpperCase(java.util.Locale.ROOT);
                            }
                        }
                        """)
                .add("FruitRepository", "package shop;\n\npublic class FruitRepository "
                        + "implements io.quarkus.hibernate.orm.panache.PanacheRepository<Fruit> {\n}\n")
                .add("ExtensionsService", "package shop;\n\n@org.eclipse.microprofile.rest.client.inject.RegisterRestClient\n"
                        + "public interface ExtensionsService {\n    String byId(String id);\n}\n")
                .add("FruitService", """
                        package shop;

                        public class FruitService {
                            private final FruitRepository repository;
                            private int calls;

                            public FruitService(FruitRepository repository) {
                                this.repository = repository;
                            }

                            public java.util.List<Fruit> all() {
                                calls++;
                                return repository.listAll();
                            }
                        }
                        """)
                .add("FruitCatalog", """
                        package shop;

                        public class FruitCatalog {
                            private int calls;

                            public java.util.List<Fruit> all() {
                                calls++;
                                return Fruit.listAll();
                            }
                        }
                        """)
                .add("FruitLabels", """
                        package shop;

                        public class FruitLabels {
                            private int calls;

                            public String label(Fruit fruit) {
                                calls++;
                                return fruit.label();
                            }
                        }
                        """)
                .add("Extensions", """
                        package shop;

                        public class Extensions {
                            private final ExtensionsService client;
                            private String last;

                            public Extensions(ExtensionsService client) {
                                this.client = client;
                            }

                            public String find(String id) {
                                last = client.byId(id);
                                return last;
                            }
                        }
                        """)
                .addTest("FruitServiceTest", """
                        package shop;

                        class FruitServiceTest {
                            private final FruitRepository repository = org.mockito.Mockito.mock(FruitRepository.class);

                            @org.junit.jupiter.api.Test
                            void lists() {
                                org.assertj.core.api.Assertions.assertThat(new FruitService(repository).all()).isEmpty();
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Extensions is service-like, but its field 'last' isn't final",
                "FruitCatalog is service-like, but its field 'calls' isn't final",
                "FruitService is service-like, but its field 'calls' isn't final");
        assertThat(guard.tests().testsDontMockValues().evaluate()).isEmpty();
    }

    @Test
    void micronautRepositoriesDeclarativeClientsCloudSdksAndDataGridsTouchTheOutsideWorld() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("GenericRepository", "package io.micronaut.data.repository;\n\n"
                        + "public interface GenericRepository<E, I> {\n}\n")
                .add("CrudRepository", "package io.micronaut.data.repository;\n\n"
                        + "public interface CrudRepository<E, I> extends GenericRepository<E, I> {\n    E save(E entity);\n}\n")
                .add("Client", "package io.micronaut.http.client.annotation;\n\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface Client {\n    String value();\n}\n")
                .add("SdkClient", "package software.amazon.awssdk.core;\n\npublic interface SdkClient {\n}\n")
                .add("S3Client", "package software.amazon.awssdk.services.s3;\n\n"
                        + "public interface S3Client extends software.amazon.awssdk.core.SdkClient {\n"
                        + "    String getObject(String key);\n\n    static S3ClientBuilder builder() {\n"
                        + "        return null;\n    }\n}\n")
                .add("IMap", "package com.hazelcast.map;\n\npublic interface IMap<K, V> {\n    V get(Object key);\n}\n")
                .add("EventLog", "package shop;\n\npublic class EventLog {\n"
                        + "    private final com.hazelcast.map.IMap<String, String> events;\n    private String last;\n\n"
                        + "    public EventLog(com.hazelcast.map.IMap<String, String> events) {\n        this.events = events;\n    }\n\n"
                        + "    public String find(String id) {\n        last = events.get(id);\n        return last;\n    }\n}\n")
                .add("S3ClientBuilder", "package software.amazon.awssdk.services.s3;\n\n"
                        + "public interface S3ClientBuilder {\n    S3ClientBuilder region(String region);\n\n    S3Client build();\n}\n")
                .add("StorageConfig", """
                        package shop;

                        public class StorageConfig {
                            private String region;

                            public void setRegion(String region) {
                                this.region = region;
                            }

                            public software.amazon.awssdk.services.s3.S3Client s3() {
                                return software.amazon.awssdk.services.s3.S3Client.builder().region(region).build();
                            }
                        }
                        """)
                .add("UserRepository", "package shop;\n\npublic interface UserRepository "
                        + "extends io.micronaut.data.repository.CrudRepository<String, Long> {\n}\n")
                .add("BillingClient", "package shop;\n\n@io.micronaut.http.client.annotation.Client(\"/billing\")\n"
                        + "public interface BillingClient {\n    String invoice(String id);\n}\n")
                .add("Registration", """
                        package shop;

                        public class Registration {
                            private final UserRepository users;
                            private int registered;

                            public Registration(UserRepository users) {
                                this.users = users;
                            }

                            public String register(String name) {
                                registered++;
                                return users.save(name);
                            }
                        }
                        """)
                .add("Billing", """
                        package shop;

                        public class Billing {
                            private final BillingClient client;
                            private String last;

                            public Billing(BillingClient client) {
                                this.client = client;
                            }

                            public String invoice(String id) {
                                last = client.invoice(id);
                                return last;
                            }
                        }
                        """)
                .add("Archive", """
                        package shop;

                        public class Archive {
                            private final software.amazon.awssdk.services.s3.S3Client s3;
                            private int reads;

                            public Archive(software.amazon.awssdk.services.s3.S3Client s3) {
                                this.s3 = s3;
                            }

                            public String read(String key) {
                                reads++;
                                return s3.getObject(key);
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Archive is service-like, but its field 'reads' isn't final",
                "Billing is service-like, but its field 'last' isn't final",
                "EventLog is service-like, but its field 'last' isn't final",
                "Registration is service-like, but its field 'registered' isn't final");
    }

    @Test
    void mapperInterfacesInThePackagesMyBatisScansQuery() {
        String holder = """
                package shop.%1$s;

                public final class %2$s {
                    private final %3$s source;
                    private String last;

                    public %2$s(%3$s source) {
                        this.source = source;
                    }

                    public String find(long id) {
                        last = source.find(id);
                        return last;
                    }
                }
                """;
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("MapperScan", """
                        package org.mybatis.spring.annotation;

                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                        public @interface MapperScan {
                            String[] value() default {};

                            String[] basePackages() default {};

                            Class<?>[] basePackageClasses() default {};
                        }
                        """)
                .add("MapperConfig", "package shop.config;\n\n@org.mybatis.spring.annotation.MapperScan(\"shop.**.mapper\")\n"
                        + "public class MapperConfig {\n}\n")
                .add("DaoConfig", "package shop.config;\n\n"
                        + "@org.mybatis.spring.annotation.MapperScan(basePackageClasses = shop.billing.dao.InvoiceDao.class)\n"
                        + "public class DaoConfig {\n}\n")
                .add("OrderMapper", "package shop.orders.mapper;\n\npublic interface OrderMapper {\n    String find(long id);\n}\n")
                .add("InvoiceDao", "package shop.billing.dao;\n\npublic interface InvoiceDao {\n    String find(long id);\n}\n")
                .add("OrderView", "package shop.orders.view;\n\npublic interface OrderView {\n    String find(long id);\n}\n")
                .add("Orders", holder.formatted("orders", "Orders", "shop.orders.mapper.OrderMapper"))
                .add("Invoices", holder.formatted("billing", "Invoices", "shop.billing.dao.InvoiceDao"))
                .add("Views", holder.formatted("orders", "Views", "shop.orders.view.OrderView"))
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Invoices is service-like, but its field 'last' isn't final",
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
}
