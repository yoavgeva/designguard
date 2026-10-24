package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which library APIs touch the outside world, beyond the JDK's: databases, HTTP clients, message brokers and mail. A
 * type counts if it is one of them or extends one, as a Spring Data repository the project declares does: the library
 * implements it at run time, so the project has no code of its own that shows the I/O. So do the interfaces a library
 * implements from annotations: MyBatis {@code @Mapper}s, OpenFeign {@code @FeignClient}s and Spring HTTP interfaces.
 * A type the project implements, as a library checking its own code or Hibernate implementing JPA's
 * {@code EntityManager} does, is judged by that implementation's code instead.
 */
final class LibraryEffects {

    private static final Set<String> IMPURE_TYPES = Set.of(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.jdbc.core.JdbcAggregateOperations",
            "org.springframework.data.r2dbc.core.R2dbcEntityOperations",
            "org.springframework.data.mongodb.core.MongoOperations",
            "org.springframework.data.mongodb.core.ReactiveMongoOperations",
            "org.springframework.data.redis.core.RedisOperations",
            "org.springframework.data.redis.core.ReactiveRedisOperations",
            "org.springframework.data.elasticsearch.core.ElasticsearchOperations",
            "org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations",
            "org.springframework.data.cassandra.core.CassandraOperations",
            "jakarta.persistence.EntityManager", "javax.persistence.EntityManager",
            "jakarta.persistence.EntityManagerFactory", "javax.persistence.EntityManagerFactory",
            "jakarta.persistence.Query", "javax.persistence.Query",
            "org.hibernate.SharedSessionContract", "org.hibernate.SessionFactory",
            "org.springframework.jdbc.core.JdbcOperations",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
            "org.springframework.jdbc.core.simple.JdbcClient",
            "org.springframework.jdbc.core.simple.SimpleJdbcInsertOperations",
            "org.springframework.jdbc.core.simple.SimpleJdbcCallOperations",
            "org.springframework.r2dbc.core.DatabaseClient", "io.r2dbc.spi.Connection", "io.r2dbc.spi.ConnectionFactory",
            "org.jooq.DSLContext", "org.apache.ibatis.session.SqlSession",
            "com.mongodb.client.MongoClient", "com.mongodb.client.MongoDatabase", "com.mongodb.client.MongoCollection",
            "org.springframework.transaction.TransactionManager",
            "org.springframework.transaction.support.TransactionOperations",
            "org.springframework.transaction.reactive.TransactionalOperator",
            "org.springframework.web.client.RestOperations", "org.springframework.web.client.RestClient",
            "org.springframework.web.reactive.function.client.WebClient",
            "okhttp3.Call", "okhttp3.Call$Factory",
            "org.apache.http.client.HttpClient", "org.apache.hc.client5.http.classic.HttpClient",
            "org.apache.hc.client5.http.async.HttpAsyncClient",
            "org.springframework.kafka.core.KafkaOperations", "org.apache.kafka.clients.producer.Producer",
            "org.apache.kafka.clients.consumer.Consumer",
            "org.springframework.amqp.core.AmqpTemplate", "org.springframework.jms.core.JmsOperations",
            "org.springframework.mail.MailSender", "jakarta.mail.Transport", "javax.mail.Transport");
    /** Annotations on a project interface that a library implements with I/O. */
    private static final Set<String> CLIENT_ANNOTATIONS = Set.of("org.apache.ibatis.annotations.Mapper",
            "org.springframework.cloud.openfeign.FeignClient", "org.springframework.web.service.annotation.HttpExchange");
    /** Packages of the annotations on the methods of an HTTP interface, as {@code @GetExchange} and Retrofit's are. */
    private static final Set<String> CLIENT_METHOD_ANNOTATIONS = Set.of("org.springframework.web.service.annotation",
            "retrofit2.http");

    private final Memo<Set<String>> implemented;
    private final Map<String, Boolean> impureTypes = new ConcurrentHashMap<>();

    LibraryEffects(ProjectClasses classes) {
        this.implemented = new Memo<>(() -> classes.stream()
                .filter(Hierarchy::isConcrete)
                .flatMap(LibraryEffects::selfAndSupertypes)
                .map(JavaClass::getName)
                .collect(Collectors.toUnmodifiableSet()));
    }

    /** Whether using this type at all means touching the outside world, like a repository or a REST client. */
    boolean isImpureType(JavaClass type) {
        return impureTypes.computeIfAbsent(type.getName(), name -> !implemented.get().contains(name)
                && selfAndSupertypes(type).anyMatch(supertype -> IMPURE_TYPES.contains(supertype.getName()) || isClient(supertype)));
    }

    private static Stream<JavaClass> selfAndSupertypes(JavaClass type) {
        return Stream.concat(Stream.of(type),
                Stream.concat(type.getAllRawSuperclasses().stream(), type.getAllRawInterfaces().stream()));
    }

    private static boolean isClient(JavaClass type) {
        return type.getAnnotations().stream()
                .anyMatch(annotation -> CLIENT_ANNOTATIONS.contains(annotation.getRawType().getName()))
                || type.isInterface() && type.getMethods().stream()
                        .flatMap(method -> method.getAnnotations().stream())
                        .anyMatch(annotation -> CLIENT_METHOD_ANNOTATIONS.contains(annotation.getRawType().getPackageName()));
    }
}
