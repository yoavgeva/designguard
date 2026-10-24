package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which library APIs touch the outside world, beyond the JDK's: databases, HTTP clients, message brokers and mail. A
 * type counts if it is one of them or extends one, as a Spring Data repository the project declares does: the library
 * implements it at run time, so the project has no code of its own that shows the I/O. So do the interfaces a library
 * implements from annotations: MyBatis {@code @Mapper}s, OpenFeign {@code @FeignClient}s, Spring HTTP interfaces,
 * MicroProfile REST clients and Micronaut's declarative HTTP, Kafka and RabbitMQ clients. A type the project implements, as a library checking its own code or Hibernate implementing
 * JPA's {@code EntityManager} does, is judged by that implementation's code instead, except a Panache repository, whose
 * methods the library supplies. An active-record entity is data, but the persistence methods it inherits from
 * Panache's {@code PanacheEntityBase}, such as {@code listAll()} and {@code persist()}, query.
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
            "org.springframework.mail.MailSender", "jakarta.mail.Transport", "javax.mail.Transport",
            "io.vertx.sqlclient.SqlClient", "io.vertx.sqlclient.Query", "io.vertx.mutiny.sqlclient.SqlClient",
            "io.vertx.mutiny.sqlclient.Query", "io.vertx.ext.web.client.WebClient", "io.vertx.mutiny.ext.web.client.WebClient",
            "com.mongodb.reactivestreams.client.MongoClient", "com.mongodb.reactivestreams.client.MongoDatabase",
            "com.mongodb.reactivestreams.client.MongoCollection",
            "org.eclipse.microprofile.reactive.messaging.Emitter", "io.smallrye.reactive.messaging.MutinyEmitter",
            "io.quarkus.mailer.Mailer", "io.quarkus.mailer.reactive.ReactiveMailer",
            "io.quarkus.redis.datasource.RedisDataSource", "io.quarkus.redis.datasource.ReactiveRedisDataSource",
            "io.micronaut.data.repository.GenericRepository", "io.micronaut.data.operations.RepositoryOperations",
            "io.micronaut.http.client.HttpClient", "io.micronaut.email.EmailSender",
            "io.lettuce.core.AbstractRedisClient", "io.lettuce.core.api.StatefulConnection", "redis.clients.jedis.UnifiedJedis",
            "redis.clients.jedis.Jedis", "co.elastic.clients.elasticsearch.ElasticsearchClient",
            "software.amazon.awssdk.core.SdkClient", "com.amazonaws.AmazonWebServiceClient");
    /**
     * Repositories a project class implements without code of its own: the library's default methods, which Quarkus
     * completes at build time, run the queries.
     */
    private static final Set<String> REPOSITORY_MIXINS = Set.of("io.quarkus.hibernate.orm.panache.PanacheRepositoryBase",
            "io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase", "io.quarkus.mongodb.panache.PanacheMongoRepositoryBase",
            "io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepositoryBase");
    /** Base classes of active-record entities, whose own methods, such as {@code listAll()} and {@code persist()}, query. */
    private static final Set<String> ACTIVE_RECORDS = Set.of("io.quarkus.hibernate.orm.panache.PanacheEntityBase",
            "io.quarkus.hibernate.reactive.panache.PanacheEntityBase", "io.quarkus.mongodb.panache.PanacheMongoEntityBase",
            "io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntityBase");
    /** Annotations on a project interface that a library implements with I/O. */
    private static final Set<String> CLIENT_ANNOTATIONS = Set.of("org.apache.ibatis.annotations.Mapper",
            "org.springframework.cloud.openfeign.FeignClient", "org.springframework.web.service.annotation.HttpExchange",
            "org.eclipse.microprofile.rest.client.inject.RegisterRestClient", "io.micronaut.http.client.annotation.Client",
            "io.micronaut.configuration.kafka.annotation.KafkaClient", "io.micronaut.rabbitmq.annotation.RabbitClient");
    /** Packages of the annotations on the methods of an HTTP interface, as {@code @GetExchange} and Retrofit's are. */
    private static final Set<String> CLIENT_METHOD_ANNOTATIONS = Set.of("org.springframework.web.service.annotation",
            "retrofit2.http");

    private static final Pattern SETTER = Pattern.compile("set[A-Z].*");

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
        return impureTypes.computeIfAbsent(type.getName(), name ->
                selfAndSupertypes(type).anyMatch(supertype -> REPOSITORY_MIXINS.contains(supertype.getName()))
                        || !implemented.get().contains(name) && selfAndSupertypes(type)
                                .anyMatch(supertype -> IMPURE_TYPES.contains(supertype.getName()) || isAnnotatedClient(supertype)));
    }

    private static Stream<JavaClass> selfAndSupertypes(JavaClass type) {
        return Stream.concat(Stream.of(type),
                Stream.concat(type.getAllRawSuperclasses().stream(), type.getAllRawInterfaces().stream()));
    }

    private static boolean isAnnotatedClient(JavaClass type) {
        return type.getAnnotations().stream()
                .anyMatch(annotation -> CLIENT_ANNOTATIONS.contains(annotation.getRawType().getName()))
                || type.isInterface() && type.getMethods().stream()
                        .flatMap(method -> method.getAnnotations().stream())
                        .anyMatch(annotation -> CLIENT_METHOD_ANNOTATIONS.contains(annotation.getRawType().getPackageName()));
    }

    /** Whether the class is an active-record entity, as a Panache entity is: data that inherits its own queries. */
    static boolean isActiveRecord(JavaClass type) {
        return type.getAllRawSuperclasses().stream().anyMatch(superclass -> ACTIVE_RECORDS.contains(superclass.getName()));
    }

    /** Whether the type is a library client: using it at all touches the outside world. */
    boolean isClientType(JavaClass type) {
        return JdkEffects.canHaveEffects(type) && isImpureType(type);
    }

    /**
     * Whether the access calls a library client, such as a repository or a REST client. Creating one or its builder, as
     * {@code new RestTemplate()} and {@code WebClient.builder()} do, isn't I/O, and neither is configuring one with a
     * setter or a fluent method, as {@code redisTemplate.setKeySerializer(...)} and {@code insert.withTableName(...)} do.
     */
    boolean isCall(JavaCodeUnitAccess<?> access) {
        CodeUnitAccessTarget target = access.getTarget();
        boolean constructs = JavaConstructor.CONSTRUCTOR_NAME.equals(target.getName());
        return isClientType(access.getTargetOwner()) && !constructs && !createsClient(target) && !configures(target)
                || !constructs && target.resolveMember()
                        .filter(member -> ACTIVE_RECORDS.contains(member.getOwner().getName())).isPresent();
    }

    /** Whether the call configures the client: a setter, or a fluent method returning it, as {@code withTableName} is. */
    private static boolean configures(CodeUnitAccessTarget target) {
        return SETTER.matcher(target.getName()).matches() && target.getRawReturnType().isEquivalentTo(void.class)
                || target.getRawReturnType().equals(target.getOwner());
    }

    /**
     * Whether the target is a static factory of a client or of its builder, as {@code WebClient.create(url)} and
     * {@code S3Client.builder()} are.
     */
    private boolean createsClient(CodeUnitAccessTarget target) {
        JavaClass owner = target.getOwner();
        return target.resolveMember()
                .filter(member -> member.getModifiers().contains(JavaModifier.STATIC))
                .map(JavaCodeUnit::getRawReturnType)
                .filter(type -> type.equals(owner) || type.getName().startsWith(owner.getName() + "$") || isClientType(type)
                        || type.getSimpleName().endsWith("Builder"))
                .isPresent();
    }
}
