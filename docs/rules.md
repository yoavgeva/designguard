# designguard rules

Every rule, by the id its violations carry: what it reports, why, an example of code that breaks it with the
message and fix designguard prints, and how to fix the code. Rules marked **recommended** run in
`GUARD.recommended()`.

Every rule takes `that().resideInAPackage(...)`, `excluding(...)`, `ignoring(annotatedWith(...))`, `asWarning()` and
`frozen(name)`; see the [README](../README.md). The examples leave out imports and use the package `shop`.

## Terms

- **Effectful**: code that touches the outside world: I/O, the clock, randomness, the environment, threads and
  executors, processes, a library's database, HTTP, messaging or mail client, or a package given to
  `treatingAsImpure(...)`. The library clients are Spring Data repositories, including the ones a project declares,
  JPA's `EntityManager`, Hibernate sessions, Spring's `JdbcTemplate`, `JdbcClient`, R2DBC `DatabaseClient`, Mongo,
  Redis, Elasticsearch and Cassandra templates and transaction templates, jOOQ's `DSLContext`, MyBatis `SqlSession`s, `@Mapper`s and the interfaces in the packages a `@MapperScan` names, `RestTemplate`, `RestClient`, `WebClient`, OkHttp, Apache HttpClient, OpenFeign `@FeignClient`s and
  Spring HTTP interfaces, MicroProfile `@RegisterRestClient`s, Kafka, AMQP and JMS templates and clients, reactive messaging `Emitter`s, `MailSender` and Quarkus mailers, and from Quarkus: Panache repositories, including the ones a project class implements, the persistence methods an active-record entity inherits from `PanacheEntityBase`, such as `listAll()` and `persist()`, Vert.x SQL and web clients, and Redis data sources, and from Micronaut: Data repositories, the `HttpClient`, declarative `@Client`, `@KafkaClient` and `@RabbitClient` interfaces and `EmailSender`, plus the Lettuce and Jedis Redis clients, Elasticsearch's Java client, AWS SDK clients and Hazelcast instances and maps; a class that is such a client is service-like; creating one or its builder, as `new RestTemplate()`, `WebClient.builder()` or `S3Client.builder()` do, isn't an effect, calling it is. Code is effectful directly,
  through a field holding such a resource, or by calling effectful project code; a call through an interface is
  effectful if any implementation is, except a call on a parameter, which runs whatever implementation the caller
  passed, so `count(Storage storage)` calling `storage.load()` isn't effectful itself, and a call through an interface
  annotated `@FunctionalInterface`, which passes behaviour as data like `java.util.function`'s interfaces do, or a call
  of a JDK collection, map or iterator method, as `iterator().next()` on a project collection, which reads the
  collection it's called on, whatever other classes implementing the interface do. Reading the rows or binding the
  parameters of a JDBC `ResultSet` or `PreparedStatement` a method is given, as a row mapper or a binder does, isn't
  an effect either: the caller runs the query. Creating
  a clock or a random source, as `Clock.systemUTC()`, `new SecureRandom()` and `ThreadLocalRandom.current()` do, isn't
  an effect; reading the time or a number from it is. The clock includes `new Date()` and `Calendar.getInstance()`,
  and the environment includes `System.getProperties()` and `Integer.getInteger(...)`. Using `Path`, `URI`, `File`, an
  `InetAddress` or `InetSocketAddress`, a `java.sql.Timestamp` or an options enum such as `StandardOpenOption` as
  data, extending `SimpleFileVisitor`, encoding a URL, copying an array, holding a thread or an executor, reading the
  current thread, and restoring its interrupt flag with `Thread.currentThread().interrupt()` aren't effects; resolving
  a host name, as `new InetSocketAddress(host, port)` does, or interrupting another thread is. A stream touches the
  outside world where it is opened: on a file name, as `new FileReader("prices.txt")` and `new Scanner(file)` are,
  from a classpath resource, or as the console (`System.out`, `System.err`, `System.in`). Holding, wrapping, reading
  or writing a stream someone else opened only processes its data, so a parser over a `Reader` or a writer of JSON to
  a `Writer` is a value.
- **Service-like**: a class that is effectful, holds a service, extends a class that is effectful itself or holds one,
  or is an interface or abstract class with a service-like implementation that isn't safe to create or call: one that
  implements its methods with effectful code, does I/O anywhere, as when it loads keys at start-up, or holds a
  service. An implementation that only reads the clock or randomness in methods of its own, as one model among many
  stamping a token does, leaves the abstraction a value. A `@FunctionalInterface` isn't service-like, whatever its
  implementations do, so holding one is like holding a lambda. Neither is data that only reads the clock or
  randomness: a class whose fields hold only data and whose code does no I/O, such as a request that stamps a UUID on
  what it builds or a stopwatch. It's no shared service, so its state, creating it and depending on it are fine, and
  the clock and randomness rules report the read; tests may still mock it, to control what it reads. Persistent
  classes aren't either: JPA `@Entity`, `@Embeddable` and `@MappedSuperclass` classes, `@Document`s and `@MongoEntity`s, and Panache active-record entities are data a
  store loads and saves, so an entity that stamps its creation date with `LocalDate.now()`, and the entities holding
  it, aren't services; the clock read is reported where it happens. **Value**: any other class.
- **Collaborator**: an instance of another project type that a class holds in a field, as injected services are.
- **Generated code**: classes the build generates, such as MapStruct mappers or OpenAPI models, whose sources lie
  outside the source roots while other classes' sources are found there. Rules analyse them, so a generated DAO still
  makes its callers service-like, but report only the code people write.
- **Wiring code**: code whose job is to assemble the application, which may create and hold concrete services:
  classes with a `main` method, Spring `@Configuration` and `@SpringBootApplication` classes, Dagger `@Module`s,
  and classes in packages given to `wiringIn(...)`. Many rules skip it.
- **Slice**: a package directly below a checked package, such as `shop.billing` when checking `shop`.

## Contents

| Group | Rules |
|---|---|
| [Architecture](#architecture) | [`layered-architecture`](#layered-architecture), [`mvc-architecture`](#mvc-architecture), [`hexagonal-architecture`](#hexagonal-architecture), [`onion-architecture`](#onion-architecture), [`feature-slices`](#feature-slices), [`class-cycles`](#class-cycles), [`thin-adapters`](#thin-adapters), [`frameworks-stay-out`](#frameworks-stay-out), [`domain-exceptions`](#domain-exceptions), [`entities-stay-behind`](#entities-stay-behind) |
| [Dependencies](#dependencies) | [`interface-policy`](#interface-policy), [`inject-services`](#inject-services), [`no-queries-in-loops`](#no-queries-in-loops), [`constructors-do-no-work`](#constructors-do-no-work), [`inject-clock`](#inject-clock), [`inject-randomness`](#inject-randomness), [`inject-configuration`](#inject-configuration), [`no-raw-threads`](#no-raw-threads), [`stable-dependencies`](#stable-dependencies) |
| [Types](#types) | [`type-dispatch`](#type-dispatch), [`immutable-records`](#immutable-records), [`data-classes-are-records`](#data-classes-are-records), [`immutable-values`](#immutable-values), [`stateless-services`](#stateless-services), [`typed-identifiers`](#typed-identifiers), [`enums-over-constants`](#enums-over-constants), [`focused-interfaces`](#focused-interfaces), [`sealed-hierarchies`](#sealed-hierarchies), [`inheritance`](#inheritance) |
| [API](#api) | [`no-flag-arguments`](#no-flag-arguments), [`null-marked`](#null-marked), [`optional-usage`](#optional-usage), [`no-checked-exceptions`](#no-checked-exceptions), [`no-effectful-static-methods`](#no-effectful-static-methods), [`typed-apis`](#typed-apis), [`no-stringly-typed-choices`](#no-stringly-typed-choices), [`accessors-change-nothing`](#accessors-change-nothing), [`minimal-visibility`](#minimal-visibility) |
| [Guardrails](#guardrails) | [`no-stubs`](#no-stubs), [`no-suppressions`](#no-suppressions), [`no-widened-for-tests`](#no-widened-for-tests), [`services-have-tests`](#services-have-tests), [`no-unused-public-code`](#no-unused-public-code) |
| [Tests](#tests) | [`meaningful-tests`](#meaningful-tests), [`no-disabled-tests`](#no-disabled-tests), [`tests-check-results`](#tests-check-results), [`tests-use-their-subject`](#tests-use-their-subject), [`tests-dont-mock-values`](#tests-dont-mock-values), [`tests-have-no-logic`](#tests-have-no-logic), [`tests-fixed-clock`](#tests-fixed-clock), [`tests-seeded-randomness`](#tests-seeded-randomness), [`tests-no-environment`](#tests-no-environment), [`tests-no-sleep`](#tests-no-sleep) |

## Architecture

### `layered-architecture`

`architecture().layered().layer(name, packages...)`, optionally `.strict()`

Layers are declared top to bottom. Each may use the layers below it, or with `strict()` only the one directly below.
A class depending on a layer its layer may not use is reported. Classes outside every layer aren't checked.

Why: a lower layer that reaches up can't be changed, tested or reused without the layers above it.

```java
GUARD.architecture().layered()
        .layer("Web", "..web..")
        .layer("Persistence", "..persistence..")
        .check();
```

```java
package shop.persistence;

public class OrderRepository {
    public String notify(shop.web.OrderController controller) {   // Persistence uses Web
        return controller.toString();
    }
}
```

> OrderRepository (layer Persistence) depends on OrderController (layer Web), which Persistence may not use
> -> fix: Persistence may not depend on other layers; invert the dependency with an interface in Persistence

Fix: let the lower layer own an interface, such as `OrderEvents`, that the upper layer implements, or move the call
up into the upper layer.

### `mvc-architecture`

`architecture().mvc()`, with `.controllers(...)`, `.views(...)`, `.services(...)` and `.models(...)` to move the
layers from their defaults `..controller..`, `..view..`, `..service..` and `..model..`

Controllers may use views, services and models; views and services may use models only; models use no other layer.

Why: models that know their controllers or views can't be reused or tested on their own.

```java
package shop.model;

public class Invoice {
    public String link(shop.controller.InvoiceController controller) {   // Model uses Controller
        return controller.toString();
    }
}
```

> Invoice (layer Model) depends on InvoiceController (layer Controller), which Model may not use
> -> fix: Model may not depend on other layers; invert the dependency with an interface in Model

Fix: pass the model the data it needs, or let the controller call the model instead of the other way round.

### `hexagonal-architecture`

`architecture().hexagonal().adapter(name, packages...)`, with `.domain(...)` and `.application(...)` to move them
from `..domain..` and `..application..`

The domain depends on nothing else; the application on the domain; each adapter on the application and the domain,
never on another adapter. Adapters must be declared.

Why: ports and adapters keep the core testable without its infrastructure and let adapters be swapped.

```java
GUARD.architecture().hexagonal()
        .adapter("web", "..adapter.web..")
        .adapter("persistence", "..adapter.persistence..")
        .check();
```

```java
package shop.domain.model;

public class Price {
    public String quote(shop.application.PlaceOrder useCase) {   // Domain uses Application
        return useCase.toString();
    }
}
```

> Price (layer Domain) depends on PlaceOrder (layer Application), which Domain may not use
> -> fix: Domain may not depend on other layers; invert the dependency with an interface in Domain

Fix: keep the dependency pointing inwards: the use case calls the domain, and the domain declares a port for
anything it needs from outside.

### `onion-architecture`

`architecture().onion().adapter(name, packages...)`, with `.domainModels(...)`, `.domainServices(...)` and
`.applicationServices(...)` to move the rings from `..domain.model..`, `..domain.service..` and `..application..`

Rings depend only inwards: domain model, then domain services, then application services, then adapters on the
outside, which never depend on each other. Adapters must be declared.

Why: as in a hexagonal architecture, the core stays free of what surrounds it.

```java
package shop.domain.model;

public class Price {
    public String quote(shop.application.PlaceOrder useCase) {   // the domain model uses an outer ring
        return useCase.toString();
    }
}
```

> Price (layer Domain model) depends on PlaceOrder (layer Application service), which Domain model may not use
> -> fix: Domain model may not depend on other layers; invert the dependency with an interface in Domain model

Fix: move the dependency outwards, or declare an interface in the inner ring for the outer one to implement.

### `feature-slices`

`architecture().featureSlices()`, with `.slicesBelow(packageName)` to take the slices from below another package

Package by feature: each package directly below a checked package is a feature slice. Its top-level package is its
API and its subpackages are its internals. A slice using another slice's internals is reported, and so are slices
depending on each other in a cycle.

Why: a feature is only replaceable, and its internals only changeable, if other features use its API alone.

```java
package shop.billing;

public class Biller {
    public int bill(shop.orders.internal.OrderMath math) {   // another feature's internals
        return math.sum(1, 2);
    }
}
```

> Biller (slice billing) uses OrderMath, which is internal to slice orders
> -> fix: use orders's API in package shop.orders, or move OrderMath there if other features need it

Fix: call what `shop.orders` exposes, or move `OrderMath` into its API package if other features need it.

### `class-cycles`

`architecture().classesHaveNoCycles()` · **recommended**

Classes that depend on each other, directly or around a loop, are reported once per cycle, at the first class by
name. A nested class counts as its top-level class. A type naming its own subtypes, as a sealed type's `permits`
clause or an interface's factory method does, isn't part of a cycle, nor is an annotation naming the class that
handles it, as a Bean Validation constraint names its validator with `@Constraint(validatedBy = ...)`, nor is an
entity's inverse side of a JPA association: a `@OneToMany`, `@OneToOne` or `@ManyToMany` field with `mappedBy`, as
`Owner.pets` is for `Pet.owner`, is one association with the side that maps it, so the entity's uses of the other
don't count. Two owning sides, which map the association twice, still form a cycle.

Why: classes in a cycle can only be understood, tested and changed together.

```java
public class Keeper {
    private Zoo zoo;
}

public class Zoo {
    private Keeper keeper;
}
```

> Keeper and Zoo depend on each other in a cycle
> -> fix: break the cycle: move what they share into a class of its own, or let one side depend on an interface it owns

Fix: move what both need into a third class, or let one side depend on an interface it owns.

### `thin-adapters`

`architecture().adaptersStayThin(adapterPackages...)`

Methods and constructors in adapter packages, such as web controllers or message listeners, that make more than two
decisions are reported. Decisions are `if`s, ternaries, `switch` cases and loops.

Why: an adapter translates between the outside world and the application. Decisions in it can only be tested through
HTTP or a broker; in the application or domain they're plain unit tests.

```java
package shop.web;

public class CheckoutPage {
    public String show(int items, boolean member) {
        if (items == 0) {
            return "empty";
        }
        if (member) {
            return "member";
        }
        return items > 10 ? "bulk" : "regular";
    }
}
```

> CheckoutPage.show() makes 3 decisions; an adapter should only translate and delegate
> -> fix: move the decisions into the application or domain layer, and call it from here

Fix: move the decisions into an application service, and let the adapter call it and render the result. One or two
checks, such as validating input, are fine.

### `frameworks-stay-out`

`architecture().frameworksStayOutOf(domainPackages...)`

Classes in the given packages that use framework types or annotations are reported: Spring, Jakarta EE and javax
(persistence, servlets, REST, CDI, injection, validation, JSON binding), Hibernate, Jackson, Gson, Guice, Dagger,
Micronaut, Quarkus, MicroProfile, Vert.x and Dropwizard.

Why: hexagonal, onion and clean architectures keep the domain plain Java, so it outlives framework upgrades and
tests without a container.

```java
package shop.domain;

@org.springframework.stereotype.Service
public class Pricing {
}
```

> Pricing uses the framework type Service
> -> fix: keep the domain plain Java: move the framework code to an adapter and map at the boundary

Fix: create the domain class in wiring code, or in an adapter, instead of annotating it.

### `domain-exceptions`

`architecture().infrastructureExceptionsStayOutOf(domainPackages...)`

Classes in the given packages that use infrastructure exceptions are reported: JDBC, I/O, JPA, Spring data access,
Jackson, Kafka, AWS, or the packages given to `treatingAsImpure(...)`.

Why: an `SQLException` in a domain signature ties the domain to a database and makes callers handle storage details.

```java
package shop.domain;

public interface OrderStore {
    String load(String id) throws java.sql.SQLException;
}
```

> OrderStore uses the infrastructure exception SQLException
> -> fix: catch it in an adapter and throw a domain exception instead

Fix: let the adapter implementing `OrderStore` catch the `SQLException` and throw a domain exception, such as
`OrderNotFound`.

### `entities-stay-behind`

`architecture().entitiesStayOutOf(apiPackages...)`

Classes in the given packages that use persistence entities (`@Entity`, `@Embeddable`, `@MappedSuperclass`) are
reported.

Why: an entity exposed through an API couples clients to the database schema, and lazy loading leaks out with it.

```java
package shop.web;

public class OrderPage {
    public String render(shop.persistence.OrderEntity entity) {
        return entity.toString();
    }
}
```

> OrderPage uses the entity OrderEntity
> -> fix: map it to a DTO or a domain type before it leaves the persistence layer

Fix: map the entity to a record before it leaves the persistence layer, and render that.

## Dependencies

### `interface-policy`

`dependencies().interfacePolicy(PORTS | SERVICES | ALL)`, with `.slicedBy(pattern)` to define the slices `PORTS`
guards, such as `com.shop.(*)..`

A class holding or receiving a concrete class, through a field, a constructor parameter or a method parameter, where
the policy wants an interface is reported:

- `PORTS`: a service-like class used across slices, the boundaries of onion and hexagonal ports. Classes in the
  checked package itself are in no slice.
- `SERVICES`: any service-like class, even inside one slice.
- `ALL`: any project class, values included. Exceptions and annotations are exempt.

Wiring code is exempt, and so are overrides: the method an override implements declares its parameters, so a
reconciler's `setupWith(ControllerBuilder builder)` takes what its interface says.

Why: depending on an interface lets tests pass a fake and lets the implementation change without touching callers.

```java
public class Checkout {
    private final StripeClient client;   // a concrete class that does I/O

    public Checkout(StripeClient client) {
        this.client = client;
    }
}
```

> Checkout depends on concrete service-like StripeClient (field client)
> -> fix: depend on an interface instead of StripeClient, and let the wiring code pass it in

Fix: declare `interface Payments { String charge(long cents); }`, let `StripeClient` implement it, and take
`Payments` in `Checkout`.

### `inject-services`

`dependencies().injectServices()`

Services obtained instead of injected are reported, outside wiring code:

- an effectful class created with `new`;
- a service-like class fetched through a static singleton accessor;
- a service looked up from a container: Spring's `getBean`, `ServiceLoader`, JNDI, Guice's `Injector` or CDI.

Creating an object whose effects all happen in implementations it is given brings no effects of its own, so it's
fine.

Why: a class that creates its own services can't be tested without them, or given others.

```java
public class Checkout {
    private final StripeClient client = new StripeClient();
}
```

> Checkout creates service-like StripeClient with new
> -> fix: take StripeClient as a constructor parameter, and create it only in wiring code

Fix:

```java
public class Checkout {
    private final Payments payments;

    public Checkout(Payments payments) {
        this.payments = payments;
    }
}
```

### `no-queries-in-loops`

`dependencies().noQueriesInLoops()` · **recommended**

Queries made once per item, the N+1 pattern, are reported. A query is a call on a collaborator that reaches a
database or another machine, through JDBC, the network, a library client such as a repository or a package given to
`treatingAsImpure(...)`, and returns a value; reading local files, the environment, the clock or randomness once per
item isn't a query. The collaborator is the same object for every item: a field, a getter's result, a parameter, a
static field or a local set before the loop. The query counts when it is made inside a loop, or in a lambda or method
reference handed to a stream or `forEach`, and is given the item or something made from it, such as its id or a key
built from it. Commands aren't queries: methods that return nothing, and methods named for a change, as a
repository's `save` and `delete` are, though they return what they stored, and neither is reading the next value of
the same object, as a parser's `reader.peek()` does, or retrying with a new timeout. Calls on the loop's own items, a
class's own helpers and static methods are left alone, since that per-item work can't be batched. A call counts only
if every implementation it may run touches the outside world, so a strategy such as a `Predicate` with pure
implementations isn't a query. Wiring code is exempt.

Why: one round trip per item turns a page of 50 orders into 51 database or HTTP calls.

```java
public long total(List<String> items) {
    long total = 0;
    for (String item : items) {
        total += prices.priceOf(item);
    }
    return total;
}
```

> Checkout.total() queries PriceRepository.priceOf() inside a loop, once per item
> -> fix: load what the loop needs with one call before it, such as one query for all the keys

Fix: add a batch query to the collaborator, such as `prices.pricesOf(items)`, and call it once.

### `constructors-do-no-work`

`dependencies().constructorsDoNoWork()` · **recommended**

Constructors and static initializers that do work are reported: I/O, printing, threads, or a collaborator that does.
Reading the clock, randomness, environment variables or system properties is cheap and can't fail, so it isn't work
here; `inject-clock`, `inject-randomness` and `inject-configuration` report those reads. Field initializers run in
every constructor, and are reported once. A call to a collaborator counts only if every implementation it may run
touches the outside world, and calls to JDK interfaces such as `Iterator.hasNext()` don't count. Reading a stream the
constructor is given, wrapping it, or creating an in-memory one such as `StringWriter`, isn't I/O, and neither is
creating a client or a clock, as `new RestTemplate()` and `Clock.systemUTC()` do, or configuring a client with a
setter or a fluent method such as `withTableName(...)`. Creating objects is left to `inject-services`, and wiring
code is exempt.

Why: a class whose constructor does work can't be created without it, so tests can't create it cheaply, and a
failure there leaves no object to recover with.

```java
public class StripeClient {
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
}
```

> StripeClient does work in its constructor through HttpClient.newHttpClient()
> -> fix: pass in what HttpClient.newHttpClient() produces, or call it from a method when it's needed

Fix: take the `HttpClient` as a constructor parameter, and create it in wiring code.

### `inject-clock`

`dependencies().clockIsInjected()`

Code outside wiring that reads the system clock is reported, once per class at its first use: `now()` without a
`Clock`, `System.currentTimeMillis()`, `Clock.systemUTC()` and the other system clocks, `new Date()`,
`Calendar.getInstance()`.

Why: code that reads the clock gives different results on every run, so its tests can't pin the time.

```java
public long now() {
    return java.time.Instant.now().toEpochMilli();
}
```

> Effects reads the system clock through Instant.now()
> -> fix: inject a java.time.Clock and pass it in, e.g. Instant.now(clock)

Fix: take a `java.time.Clock` in the constructor and call `Instant.now(clock)`. Tests pass `Clock.fixed(...)`.

### `inject-randomness`

`dependencies().randomnessIsInjected()`

Code outside wiring that creates randomness is reported, once per class at its first use: `new Random()`,
`new SecureRandom()`, `Math.random()`, `UUID.randomUUID()`, `ThreadLocalRandom.current()`,
`RandomGenerator.getDefault()` and `RandomGenerator.of(...)`.

Why: random values can't be asserted on, and a failing case may never come back.

```java
public String id() {
    return java.util.UUID.randomUUID().toString();
}
```

> Effects generates randomness through UUID.randomUUID()
> -> fix: inject an ID supplier, e.g. a Supplier<UUID>, and call it

Fix: take a `Supplier<UUID>` or a `RandomGenerator` in the constructor. Tests pass a fixed one.

### `inject-configuration`

`dependencies().configurationIsInjected()`

Code outside wiring that reads environment variables or system properties is reported, once per class at its first
use: `System.getenv`, `getProperty` and `getProperties`, and `Integer.getInteger`, `Long.getLong` and
`Boolean.getBoolean`, which read system properties too.

Why: configuration read deep inside the code is invisible to callers and can't be varied in tests.

```java
public String region() {
    return System.getenv("REGION");
}
```

> Effects reads the environment through System.getenv()
> -> fix: read configuration once in wiring code and inject the values

Fix: read the variable in wiring code and pass the value to the constructor.

### `no-raw-threads`

`dependencies().noRawThreads()` · **recommended**

Code outside wiring that starts threads by hand is reported: `new Thread(...)`, `Thread.startVirtualThread(...)`, and
`start` or `unstarted` on a thread builder such as `Thread.ofVirtual()`. A thread factory makes an executor's
threads, so creating them there isn't: a method that takes the work and returns its thread, as
`ThreadFactory.newThread(Runnable)` does, or a lambda or `Thread::new` an executor takes as its `ThreadFactory`.

Why: raw threads aren't pooled, bounded or shut down, and tests can't run their work synchronously.

```java
public void run(Runnable job) {
    new Thread(job).start();
}
```

> Effects starts a thread directly through new Thread()
> -> fix: submit the work to an injected ExecutorService, e.g. one from Executors.newFixedThreadPool(n)

Fix: take an `ExecutorService` in the constructor and submit the work to it. On Java 21 the fix suggests
`Executors.newVirtualThreadPerTaskExecutor()`.

### `stable-dependencies`

`dependencies().stableDependencies()`

The Stable Dependencies Principle: depend in the direction of stability. A slice's instability is
`I = Ce / (Ca + Ce)`, where `Ce` counts its classes that depend on other slices and `Ca` counts other slices' classes
that depend on it. A slice depending on a slice with a higher `I` is reported.

Why: a stable slice, one many others use, should be hard to change. If it depends on a volatile slice, every
change there ripples through everything that uses it.

> slice core (instability 0.33) depends on slice reports, which is less stable (instability 0.50)
> -> fix: invert it: let core own an interface that reports implements, or move what core needs into core

Fix: let the stable slice own an interface that the volatile one implements, or move what it needs into it.

## Types

### `type-dispatch`

`types().typeDispatch(SEALED_ONLY | ALLOW)`, with `.maxPlacesPerType(n)` (default 3)

Branching on the kind of a project type that breaks when a new kind is added is reported:

- with `SEALED_ONLY`, a `switch` or `instanceof` chain on the subtypes of a type that isn't sealed. This needs code
  that targets Java 17;
- a `default` branch in a switch over a sealed type or an enum, which hides new cases;
- an `instanceof` chain;
- a type branched on in more unchecked places than `maxPlacesPerType` allows, reported once at its declaration.

Why: when a new kind is added, every branch on the kind needs a new case. The compiler finds them only in exhaustive
switches over sealed types and enums.

```java
public String sound(Animal animal) {
    if (animal instanceof Cat) {
        return "meow";
    } else if (animal instanceof Dog) {
        return "woof";
    }
    return "";
}
```

> instanceof chain on the subtypes of Animal, which isn't sealed, so a new subtype won't be caught
> -> fix: make Animal sealed, or move the varying behaviour into Animal

Fix: `sealed interface Animal permits Cat, Dog`, and an exhaustive `switch (animal) { case Cat c -> ...; case Dog d ->
...; }` on Java 21. Or give `Animal` a `sound()` method.

### `immutable-records`

`types().recordsAreImmutable()` · **recommended**

Record components callers can still change are reported: collections and maps the constructor doesn't copy into
unmodifiable ones, arrays, mutable JDK types like `Date`, and the project's mutable data classes, those with setters
or fields that aren't private and final. An unmodifiable view of the caller's collection, as
`Collections.unmodifiableList(items)` is, isn't a copy: it changes when the caller changes the list.

Why: a record is only a value if it is deeply immutable. Otherwise a caller holding the list changes it, and
equality with it.

```java
public record Basket(List<String> items) {
}
```

> record Basket's component 'items' (java.util.List<String>) can be changed by callers after construction
> -> fix: copy it in a compact constructor: items = List.copyOf(items);

Fix:

```java
public record Basket(List<String> items) {
    public Basket {
        items = List.copyOf(items);
    }
}
```

### `data-classes-are-records`

`types().dataClassesAreRecords()`

Classes that only carry data are reported: every instance field is `private final`, and every method is an
accessor, `equals`, `hashCode`, `toString` or static. Classes that extend another class, inner classes and
persistence entities are skipped, since they can't be records. This needs code that targets Java 16.

Why: a record says it is data, and gets correct `equals`, `hashCode` and `toString` for free.

```java
public final class Point {
    private final int x;
    private final int y;

    public Point(int x, int y) { this.x = x; this.y = y; }
    public int getX() { return x; }
    public int getY() { return y; }
}
```

> Point only carries data: make it a record
> -> fix: replace it with record Point(int x, int y)

Fix: `public record Point(int x, int y) {}`.

### `immutable-values`

`types().valueTypesAreImmutable()`

Values with a non-final instance field are reported. A field a container injects, with `@Autowired`, `@Value` and the
like, is reported as one to take as a constructor parameter. Enums, records, exceptions, wiring code, persistence
entities and builders are skipped, and so are the other fields of components a container manages, which aren't
values: Spring beans, including stereotypes such as `@RestController`, CDI and `@Singleton` beans, Micronaut
controllers, JAX-RS resources and providers, AspectJ aspects, WebSocket endpoints, servlets and filters, and
command-line commands. A builder changes on purpose, and what it builds is the value: it has a `build()` that returns
something else, or fluent setters that return the builder and either a name ending in `Builder` or a `build...` or
`create...` method that returns something else.

Why: a value that changes can't be shared or used as a map key, and changes behind its holders' backs.

```java
public class Tally {
    private int total;

    public void add(int amount) {
        total += amount;
    }
}
```

> Tally is a value, but its field 'total' isn't final
> -> fix: make the field final and return a new Tally instead of changing it, or turn Tally into a record

Fix: `record Tally(int total) { Tally add(int amount) { return new Tally(total + amount); } }`.

### `stateless-services`

`types().servicesAreStateless()`

Service-like classes that hold changing state are reported: a non-final instance field, or a final one holding a
mutable collection, an atomic or a string builder. A non-final field a container injects, with `@Autowired`,
`@Value`, `@Inject`, `@Resource` or `@PersistenceContext`, holds a collaborator or a setting, not state, so it is
reported as one to take as a constructor parameter, and so is a field only its setter sets, as `setWebhook(...)` sets
`webhook`: a setting a container binds, as Spring binds configuration properties. Wiring code, enums and records are
skipped, and so is what a framework creates for each use, which holds the state of that use: an aesh or picocli
command, a WebSocket endpoint, and a request- or session-scoped bean.

Why: a service is shared by its callers, so its state leaks between them and races under concurrency.

```java
public class PaymentLog {
    private int written;

    public void write(String line) {
        written++;
        System.out.println(line + written);
    }
}
```

> PaymentLog is service-like, but its field 'written' isn't final
> -> fix: keep state out of services: pass it in and return it as values, or keep it in a store the service calls

Fix: pass the count in and return the new one, or keep it in a store the service calls.

### `typed-identifiers`

`types().typedIdentifiers(domainPackages...)`

Identifiers typed as raw strings, numbers or UUIDs in the given packages are reported: fields, record components,
and non-private method and constructor parameters named `id` or `somethingId`. The identifier types themselves,
named `...Id`, are skipped, and so are parameters of methods that override or implement another type's, with or
without `@Override`, since that type sets the signature. The fix names the type after what the identifier identifies:
`customerId` after the customer, and a plain `id` after the thing its method names, as `getPet(id)` and
`findVisitById(id)` do, after a repository's entity, or else after the declaring type.

Why: with one type per identifier, the compiler stops a customer ID from being passed where an order ID belongs.

```java
public record Customer(String customerId) {
}
```

> Customer.customerId is a raw String identifier
> -> fix: declare record CustomerId(String value) and use it, so identifiers of different things can't be swapped

Fix: `record CustomerId(String value) {}` and `record Customer(CustomerId customerId) {}`.

### `enums-over-constants`

`types().enumsOverConstants()` · **recommended**

Groups of `static final` int, long or String constants of one type that enumerate cases are reported. They share a
name prefix, like `STATUS_OPEN` and `STATUS_PAID`, and their values are a run of numbers from 0 or 1, distinct bit
flags, or the names themselves. Constants with real values, like `MAX_ITEMS = 50` and `MAX_RETRIES = 3`, don't count,
and neither do String constants that are keys of a format: every use passes one to a lookup, as
`attributes.getValue(ATTR_PREMAIN_CLASS)` does, and none compares, switches on or assigns one. Nor do constants used
only in converters, which turn outside input into a type, as `toPermissions(int mode)` does with a file mode's bits,
or String values with syntax beyond the name, as `"rmi:"` has. Nor do the known values of an open vocabulary:
constants declared in the class named after their prefix, as `StatusInfo` is for `STATUS_UP`, which holds a value of
their type and takes any such value from callers, as `StatusInfo.valueOf(String)` takes custom health statuses that
an enum would reject.

Why: an `int` status accepts any number, and the compiler can't tell when a `switch` misses one (Effective Java,
item 34).

```java
public final class Codes {
    public static final int STATUS_OPEN = 0;
    public static final int STATUS_PAID = 1;
}
```

> Codes declares STATUS_OPEN, STATUS_PAID as int constants: make them an enum
> -> fix: replace them with an enum, e.g. enum Status { OPEN, PAID }

Fix: `enum Status { OPEN, PAID }`, and parameters of type `Status` instead of `int`.

### `focused-interfaces`

`types().interfacesAreFocused()` · **recommended**

Interfaces wider than any of their clients needs are reported: at least ten abstract methods, used by at least two
classes other than their implementations, each of which calls at most half of them. Overloads count as one method,
since no split would part them, and so do none of the methods a supertype from outside the project declares, as
`ExecutorService.submit` or `Object.equals`: the interface can't drop them. A smaller interface is one role whose
clients each use a part, as a store's reads and writes or a lifecycle's start and await are. Value types are left
out: an interface the project implements without effects, as a collection, a graph or a hash sink is, offers the
operations of one data type, and nobody fakes it. What other classes implementing a JDK collection or iterator
interface do doesn't make a project collection effectful: `iterator().next()` reads the collection it's called on.

Why: every client depends on methods it never uses, and every implementation, fakes included, has to provide them
all (the interface segregation principle).

```java
public interface Feeding {
    void feed(String animal);
    void clean(String cage);
    int count();
    String report();
    void water(String animal);
    void weigh(String animal);
    void groom(String animal);
    void vaccinate(String animal);
    void rest(String animal);
    void play(String animal);
}
// Feeder only calls feed(); Auditor only calls report()
```

> Feeding has 10 methods, but each of its 2 clients uses at most 1 of them
> -> fix: split it into smaller interfaces, one for each role its clients need, and let implementations implement several

Fix: split it into role interfaces, such as `Feeding` and `Reporting`, and let one class implement both.

### `sealed-hierarchies`

`types().closedHierarchiesAreSealed()`

Interfaces and abstract classes implemented only inside the project that aren't sealed are reported. Functional
interfaces are skipped, because lambdas can't implement a sealed interface. Meant for applications, since a
library's users may implement its interfaces. This needs code that targets Java 17.

Why: sealing lets the compiler check switches over the type for missing cases.

```java
public interface Animal {
}

public record Cat() implements Animal {
}

public record Dog() implements Animal {
}
```

> Animal is only implemented in this project, but isn't sealed
> -> fix: declare it sealed: sealed interface Animal permits Cat, Dog

Fix: `public sealed interface Animal permits Cat, Dog {}`.

### `inheritance`

`types().inheritance(FINAL_BY_DEFAULT | ABSTRACT_ONLY | ANY)`, with `.maxDepth(n)` to limit how deep hierarchies get

- `FINAL_BY_DEFAULT`: concrete classes nothing extends must be final. Design for inheritance, or prohibit it.
- `ABSTRACT_ONLY`: classes may extend abstract classes only, never a concrete class.
- `ANY`: any inheritance; only the depth limit applies.

Exceptions and framework-proxied classes (`@Service`, `@Entity`...) are skipped.

Why: composition over inheritance (Effective Java, item 18). Subclassing a class that wasn't designed for it breaks
when the class changes.

```java
public class PlaceOrder {
}
```

> PlaceOrder isn't final, and nothing extends it
> -> fix: make it final, or design it for extension and document how

Fix: `public final class PlaceOrder`.

## API

### `no-flag-arguments`

`api().noFlagArguments()` · **recommended**

Non-private methods with a boolean parameter used directly as an `if` or ternary condition are reported. Overrides
are skipped, since they inherit their signature, and so are setters, whose boolean is the value they set. So are
booleans that only guard a `throw`, as preconditions like `checkArgument(boolean condition, String message)` do, and
booleans that only pick a value: a ternary between constants (`out.write(value ? "true" : "false")`), a returned
ternary between values (`return bool ? 1 : 0;`, `return bool ? yes : no;`), or an `if` and `else` that differ only in
constants. So are booleans in an overload family for plain values, as `append(boolean)` next to `append(int)` or
`append(Object)` is: the boolean is one more kind of value the overloads handle. An overload that takes an enum
instead shows the boolean picks a mode, and passing a picked mode on, as in `load(id, eager ? EAGER : LAZY)`, still
chooses a behaviour. Booleans an HTTP request binds, with Spring's `@RequestParam`, `@PathVariable` or
`@RequestHeader` or JAX-RS's `@QueryParam` and the like, are skipped, and so are the booleans of a request handler
(`@GetMapping`, `@GET` and the like), which Spring binds even without an annotation: the HTTP API takes the flag, and
the handler only receives it.

Why: such a method does two things, and a call like `print(text, true)` doesn't say which.

```java
public String print(String text, boolean upper) {
    if (upper) {
        return text.toUpperCase(java.util.Locale.ROOT);
    }
    return text;
}
```

> Printer.print() branches on its boolean parameter 'upper'
> -> fix: split it into one method per behaviour, so callers don't pass true or false

Fix: `print(String text)` and `printUpperCase(String text)`.

### `null-marked`

`api().packagesAreNullMarked()`

Packages without JSpecify's `@NullMarked`, on their `package-info.java` or on the module, are reported.

Why: with it, every type in the package is non-null unless marked `@Nullable`, and checkers like NullAway can verify
that.

> package shop.application isn't @NullMarked, so its nullness is unspecified
> -> fix: add package-info.java to the package: @org.jspecify.annotations.NullMarked package shop.application;

Fix:

```java
// src/main/java/shop/application/package-info.java
@org.jspecify.annotations.NullMarked
package shop.application;
```

### `optional-usage`

`api().optionalUsage()` · **recommended**

`Optional` used against its design is reported: as a field, as a parameter, or returned as `null`. A static method
that only converts an `Optional` into something else, such as `Streams.stream(Optional)` or `orElseNull(Optional)`,
may take one.

Why: `Optional` is meant only as a return type saying "there may be no result". As a field or parameter it adds a
third state, a null `Optional`, and an allocation.

```java
public class Holder {
    private final java.util.Optional<String> name = java.util.Optional.empty();
}
```

> Holder has a field 'name' of type Optional
> -> fix: store the value itself, or null, and return an Optional from its accessor instead

Fix: `private final @Nullable String name;` and `Optional<String> name() { return Optional.ofNullable(name); }`.

### `no-checked-exceptions`

`api().noCheckedExceptions()`

Project exception classes that extend `Exception` but not `RuntimeException`, and non-private methods that declare
a checked exception, are reported. Methods whose signature is inherited, like `AutoCloseable.close()` or an
override of a generic method such as `write(Writer out, String value)` for `write(Writer out, T value)`, are skipped,
and so is wiring code. `InterruptedException` is fine to declare: a blocking method such as a queue's `take()` passes
the interrupt on to its caller instead of losing it in a wrapper.

Why: checked exceptions don't compose with lambdas and streams, and force every caller to handle what only one
place can.

```java
public interface OrderStore {
    String load(String id) throws java.sql.SQLException;
}
```

> OrderStore.load() declares the checked exception SQLException
> -> fix: handle it here, or rethrow it as an unchecked exception

Fix: catch it in the implementation and throw an unchecked exception, such as `UncheckedIOException` or a domain
exception.

### `no-effectful-static-methods`

`api().noEffectfulStaticMethods()`

Static methods whose own code touches the outside world, such as I/O, the clock or the environment, are reported
when other classes call them. A few cases are exempt:

- private helpers, wiring code, and calls from wiring code;
- calls from adapters, classes implementing a project interface or abstract class, since their callers can
  replace them;
- effects of implementations a method is given, or of lambdas it returns, which aren't its own.

Why: a caller can't be given a fake instead of a static method, so it can't be tested without the effect.

```java
public final class Disk {
    public static String read(java.nio.file.Path file) throws java.io.IOException {
        return java.nio.file.Files.readString(file);
    }
}

public class Lookup {
    public String load(java.nio.file.Path file) throws java.io.IOException {
        return Disk.read(file);
    }
}
```

> Disk.read() is static and touches the outside world, so callers like Lookup can't replace it in tests
> -> fix: make it an instance method of a class that callers take as a constructor parameter

Fix: make `read` an instance method, and give `Lookup` a `Disk` in its constructor.

### `typed-apis`

`api().typedApis()`

Public methods of public classes that take or return untyped data are reported: `Object`, a map to `Object` such as
`Map<String, Object>`, or a JSON tree from Jackson, Gson, org.json or Jakarta JSON, unless the project is that
library and the tree its own model. Type variables, inherited signatures such as `equals(Object)`, AspectJ `@Around`
advice, which returns whatever the advised method returns, and wiring code are skipped.

Why: callers and the compiler can't tell what to pass or what comes back. A record names the fields and their
types.

```java
public Object find(String key) {
    return key;
}
```

> Lookup.find() returns an untyped Object
> -> fix: define a record with named, typed fields and use it instead

Fix: return a record, such as `record Entry(String key, String value) {}`.

### `no-stringly-typed-choices`

`api().noStringlyTypedChoices()` · **recommended**

Choices between strings are reported: a `switch` over three or more string literals, or an `if`/`else if` chain
comparing one value with three or more strings through `equals`, `equalsIgnoreCase` or `Objects.equals`. Converters,
which turn input strings into a type, are exempt: methods named `parse`, `from`, `valueOf`, `of`, `decode`, `to...`,
`for...` or `convert...`, as `toBool`, `forExtension` and `convertCondition` are, and methods returning one of the
project's enums. So are words from outside the code, which an enum can't own: strings with dots or spaces (property
keys, class names, SQL), tokens a parser reads, any of which is punctuation such as `"("`, single characters such as
operators, Java's type names, codes with digits such as SQL states, method names a proxy dispatches on, which include
`toString`, HTTP's method names such as `"GET"` and `"POST"`, and command-line options such as `"--init"`.

Why: a typo in a string compiles, and a missing enum case doesn't.

```java
public int width(String size) {
    switch (size) {
        case "small": return 1;
        case "medium": return 2;
        case "large": return 3;
        default: return 0;
    }
}
```

> Printer.width() switches on the strings "small", "medium" and "large": make them an enum
> -> fix: replace the strings with an enum, e.g. enum Size { SMALL, MEDIUM, LARGE }, and switch over it

Fix: `enum Size { SMALL, MEDIUM, LARGE }`, converted once from the input, and a `switch` over `Size`.

### `accessors-change-nothing`

`api().accessorsChangeNothing()` · **recommended**

`get`, `is` and `has` methods that change their own instance or a static field of their class are reported: they
assign a field, change the collection or atomic it holds (`items.add(item)`, `count.incrementAndGet()`), or call a
method of the class that does. Writing the fields of another object, such as a copy the method builds, is fine.

Lazy initialization is fine too: a change made only while a field is still `null`, a flag still `false` or a value
still equal to a sentinel, that sets it. That covers `if (name == null) { name = load(); }`, `if (!loaded) { ...;
loaded = true; }`, `if (cached != null) return cached;` followed by the load, `d != null ? d : (d = load())`, double-
checked locking, caching in a map the method checks first or fills with `computeIfAbsent`, and caching in an atomic
reference whose `updateAndGet` keeps the value or replaces it with one made without it, as `ref.updateAndGet(pair ->
pair != null && pair.key().equals(key) ? pair : load(key))` does. An update built on the old value, as `count ->
count + 1`, is a change. A field set only while the method runs is fine: a flag it sets both `true` and `false`, or a
field it both sets and clears to `null`, as a query's `before()` and `after()` do with the mode they save, itself or
through the methods it calls. So are `getAnd...` methods, whose names say they change something, a cursor's
`hasNext()`, which may read ahead, void methods, a builder's setters, which take a value and return the builder, as
Lombok's `isBound(boolean)` does, and methods implementing an interface from outside the project, such as JDBC's
`Statement.getResultSet()`, whose name the interface dictates.

Why: callers read accessors as queries and call them freely, so a hidden change surprises them (command-query
separation).

```java
public int getNext() {
    return total++;
}
```

> Tally.getNext() changes the field 'total'
> -> fix: keep get, is and has methods free of side effects: move the change into a command method, or compute the value without storing it

Fix: split it into `int peek()` and `void advance()`, or name it `getAndIncrement()`.

### `minimal-visibility`

`api().minimalVisibility()`

Public top-level types that only their own package uses are reported. Unused types and wiring code are skipped.
Meant for applications, since a library's public types are its API.

Why: a package-private type keeps the package's API to what other packages may rely on.

> Disk is public, but only package shop.misc uses it
> -> fix: make it package-private

Fix: drop `public` from the class.

## Guardrails

### `no-stubs`

`guardrails().noStubs()` · **recommended**

Unfinished code is reported:
- `TODO` and `FIXME` markers that start a comment line, also after a Javadoc tag, an HTML tag or commented-out
  slashes, as in `@deprecated TODO` or `<p>TODO`, unless the comment cites the issue tracking the work, as
  `TODO(b/27479612)`, `TODO: HHH-6484`, `FIXME #42` or a link do;
- throws whose message says the code is unfinished, such as "not implemented yet" or "TODO", whatever the exception,
  and any `NotImplementedException`;
- methods whose whole body is `throw new UnsupportedOperationException()`, unless the refusal is deliberate: the
  method is `@Deprecated`, a private constructor, a hook such as an override, or its Javadoc says it's unsupported;
- empty method bodies without a comment or Javadoc saying why, unless the method is a hook or an AspectJ `@Pointcut`,
  whose body is empty by design.

A hook is an override, with or without `@Override`, an interface's default method, a method of an abstract class
that subclasses may override, or a method that a project subclass, anonymous class or enum constant body overrides.

Why: AI agents leave placeholders that compile and pass review at a glance.

```java
public class Printer {
    // TODO handle locales
    public String print(String text) {
        return text;
    }
}
```

> TODO left in the code: TODO handle locales
> -> fix: finish the work now, or track it in an issue instead of a comment

Fix: finish the work, or open an issue and remove the comment. An empty method that's empty on purpose gets a
comment saying why.

### `no-suppressions`

`guardrails().noSuppressions()`, with `.allowing(names...)` to allow warning names like `serial` or markers like
`NOSONAR` · **recommended**

Warnings silenced instead of fixed are reported, in production and test code: `@SuppressWarnings`, SpotBugs'
`@SuppressFBWarnings`, and comments starting with `NOPMD`, `NOSONAR` or a variant such as `NOSONARLINT`, `noinspection`
or `CHECKSTYLE:OFF`. Allowing `NOSONAR` allows its variants. Mentions in prose don't count.

Why: agents silence warnings to get a build green, and the problem stays.

```java
@SuppressWarnings("unchecked")
public List<String> names(Object list) {
    return (List<String>) list;
}
```

> @SuppressWarnings("unchecked") silences a warning instead of fixing it
> -> fix: fix what the tool reports instead of silencing it

Fix: fix what the tool reports, here by taking a `List<String>`.

### `no-widened-for-tests`

`guardrails().noWidenedForTests()` · **recommended**

Package-private or protected methods and constructors that tests in their package call are reported when no
production code calls them, or when they are marked `@VisibleForTesting` (any annotation of that name), which says
they were widened for tests. Overrides, members with other annotations, which a framework may call, protected
constructors, which are there for subclasses, and wiring code are skipped. A constructor no production code calls is
reported only when production creates the class through another one, which is called, public or annotated: a class's
only constructor is how its framework, as Spring creates a `@Controller`, and its tests both create it. Code nothing
calls is left to `no-unused-public-code`.

Why: the method was opened up so tests could reach inside the class, which ties the tests to its internals.

```java
public class Tally {
    private int total;

    int recount() {   // only TallyTest calls it
        return total;
    }
}
```

> Tally.recount() is package-private, and only tests call it
> -> fix: test through the public API, or move it into a class of its own whose API tests and production both use

Fix: test through the public API, or move the logic into a class of its own that production uses too.

### `services-have-tests`

`guardrails().serviceLikeClassesHaveTests()`

Service-like classes without a test class named `<Name>Test`, `<Name>Tests` or `<Name>IT` are reported. A test named
after an interface or superclass of the class counts when it uses the class by name, as `ViewNameResolverTest`
testing `DefaultViewNameResolver` does. Wiring code is skipped, and so is a class that only carries data, as an event
holding the service it is about does: its constructors only assign fields, and its methods return them or are
`equals`, `hashCode` and `toString`. Not in `recommended()`, since projects that test by feature don't follow the
convention.

Why: service-like classes hold the behaviour worth testing, and agents skip writing tests.

> Disk is service-like, but has no test class (looked for DiskTest)
> -> fix: add DiskTest that exercises its behaviour

Fix: add `DiskTest`.

### `no-unused-public-code`

`guardrails().noUnusedPublicCode()`

Public top-level types nothing depends on, and public methods nothing calls or references, are reported. Likely entry
points are skipped: wiring code, annotated types and methods, types a framework finds or that the resources packaged
with the classes name, as `META-INF/services` files and logging configuration do, inherited signatures, record
accessors, enum helpers, bean accessors (`get`, `set`, `is`), and the methods of JMX interfaces (`...MBean`,
`...MXBean`), which management clients call. Meant for applications, since in a library unused public code is the
API.

Why: agents leave code behind that nothing uses, and it still has to be read and maintained.

> Biller is public, but nothing uses it
> -> fix: delete it, or make it package-private until something needs it

Fix: delete it.

## Tests

### `meaningful-tests`

`tests().testsAssertMeaningfully()` · **recommended**

Tests that prove nothing are reported:

- tests without any assertion;
- assertions on constants, like `assertTrue(true)` or `assertThat(1).isEqualTo(1)`;
- assertions comparing a value with itself, like `assertEquals(total, total)` on a number or string, or
  `assertSame(range, range)`;
- assertions that a new object isn't null, like `assertNotNull(new Cart())`;
- tests that assert only in a `catch` block, which pass when nothing is thrown.

Assertions are calls named `assert*`, `verify*`, `expect*` or `fail*`, plus AssertJ's `then`, MockMvc's `andExpect`,
ArchUnit's and designguard's `check`, Awaitility's `until`, Java's `assert` statements, which Maven, Gradle and IDEs
enable in tests, a thrown `AssertionError`, and anything thrown when a check fails, as `if (actual != expected) throw
new IllegalStateException(...)` does. A call to a helper of the test sources that asserts counts too: a method of the
test class, of a test superclass, or of a test class the call names. So do JUnit 4's `@Test(expected = ...)`, and a
test name or comment saying it checks that nothing is thrown, such as `shouldNotThrowForAnEmptyCart`, `// nothing
thrown`, `// should not blow up` or `// concurrent modification won't happen`, and an empty test in a
`@SpringBootTest` class, like Spring's `contextLoads()`, which checks that the application starts. Constants given to
a helper of the tests are its inputs, so only library assertions count as asserting on constants. Some comparisons
with the same expression on both sides can still fail, so they don't count as self-comparisons: `assertEquals(range,
range)` on an object, which checks its `equals`, expressions with a call or `new`, which run twice, and any
comparison in a test whose name mentions `equal`, `hash` or `compar`, which checks reflexivity on purpose.

Why: a test that can't fail gives coverage without checking anything. Agents write them to get a suite green.

```java
@Test
void adds() {
    new Tally().add(1);
}
```

> test TallyTest.adds() asserts nothing
> -> fix: assert on the result of the code under test

Fix: assert on what the code under test returns or changes.

### `no-disabled-tests`

`tests().noDisabledTests()`, with `.allowingWithReason()` to allow `@Disabled("reason")` with a non-blank reason ·
**recommended**

Disabled tests are reported: JUnit's `@Disabled` and `@Ignore` on a test or its class, and TestNG's
`@Test(enabled = false)`.

Why: agents disable the tests they can't make pass.

```java
@Disabled
@Test
void later() {
    assertThat(new Tally().getNext()).isZero();
}
```

> test TallyTest.later() is disabled
> -> fix: fix and re-enable it, or delete it if it no longer matters

Fix: fix the test or the code, or delete the test if it no longer matters.

### `tests-check-results`

`tests().testsCheckResults()` · **recommended**

Tests that verify queries are reported: `verify(mock).method(...)` where every such method of the mock's type returns
a value. Commands are fine to verify: methods that return nothing or only a `Void` publisher or future, such as
`Mono<Void>`, and methods named for a change (`create`, `update`, `save`, `send`, `publish` and the like), which
reactive clients and repositories make return what they stored. A verification with a mode, such as `times(1)` or
`never()`, counts calls on purpose, so it's fine.

Why: verifying that a query was called checks how the code works, not what it returns. Stub queries and assert on
the result, and verify only commands, which return nothing.

```java
@Test
void finds() {
    lookup.find("key");
    verify(lookup).find("key");
}
```

> test LookupTest.finds() verifies Lookup.find(), a query that returns a value
> -> fix: stub it with when(...).thenReturn(...) and assert on what the code under test returns; verify only commands

Fix: `when(lookup.find("key")).thenReturn("value")`, then assert on the result of the code that uses `lookup`.

### `tests-use-their-subject`

`tests().testsUseTheirSubject()` · **recommended**

Test classes that don't exercise the class they're named after are reported: `OrderServiceTest` never uses
`OrderService`, or only mocks it. A use is the class's name in a type, a static call or an annotation, a member of it
the test imports statically, as `encode(text)` after `import static shop.Base64Url.encode`, or an expression whose
type resolves to it, such as `Streams.failableStream(list)`, in the test or in a test-source helper class it uses.
Expressions resolve against the production sources too, so a factory's result counts even when its classes aren't on
the test's class path. A field the test's container injects (`@Autowired`, `@Inject`, `@Resource`) typed as an
interface or superclass of the class uses it, and so does a test of a web controller, controller advice, filter or
JAX-RS resource or provider that sends requests through `MockMvc`, `WebTestClient`, `TestRestTemplate`, REST Assured
or an `HttpClient`, as Micronaut tests inject one. A test named after an interface or abstract class uses it through
an implementation, a spy runs the real class, a project class the test uses that creates it, as a template dialect
creates its processors, or registers it by its class, as a Jackson module registers a mixin with
`setMixInAnnotation(Money.class, MoneyMixin.class)`, uses it too, a `@SpringBootTest` boots the
`@SpringBootApplication` class, and requests a booted test (`@SpringBootTest`, `@QuarkusTest`, `@MicronautTest`)
sends reach any bean: a Spring component or CDI-scoped class, or a class the wiring code creates in a `@Bean` method.
A test extending a class that declares tests runs them, so it uses what they use, as `FruitResourceIT extends
FruitResourceTest` does. Tests named after no project class are skipped.

Why: a test named after a class that only tests mocks of it checks nothing about it.

```java
class LookupTest {
    private final Lookup lookup = mock(Lookup.class);

    @Test
    void finds() {
        lookup.find("key");
        verify(lookup).find("key");
    }
}
```

> LookupTest only mocks Lookup, the class it's named after
> -> fix: test a real Lookup, and mock only its collaborators

Fix: create a real `Lookup`, and mock only what it calls.

### `tests-dont-mock-values`

`tests().testsDontMockValues()` · **recommended**

Mocks of values are reported: records, enums and other classes that aren't service-like or wiring code, and
interfaces whose implementations are all values. Data that reads the clock or randomness may be mocked, to control
what it reads. So may a class that works through a library designguard can't see into: one that extends a library
class, holds a library object other than data, or calls one of its own, as a search engine over Lucene or a plugin
manager extending pf4j's does, since that library may do I/O. Calls on parameters belong to the caller, so an entity
that checks a password with an encoder it is given is still a value. An interface with one abstract method is a role, such as a strategy or a callback,
which tests mock to see it called, so it isn't a value. Mocks come from Mockito's or EasyMock's `mock(Type.class)`,
`spy(Type.class)`, `spy(new Type(...))` and `createMock(Type.class)`, and from fields and parameters annotated
`@Mock`, `@Spy` or Spring's mock-bean annotations.

Why: a value is cheap and safe to create, so a test should use a real one. A mock of a value tests the mock.

```java
@Test
void moves() {
    Point point = mock(Point.class);
    assertThat(point.getX()).isZero();
}
```

> PointTest mocks Point, which is a value
> -> fix: use a real Point instead; mock only service-like types

Fix: `Point point = new Point(0, 0);`.

### `tests-have-no-logic`

`tests().testsHaveNoLogic()` · **recommended**

Test methods containing an `if` or a `switch` are reported, since they check different things on different runs. So
are loops around an assertion, which hide which case failed. A loop that only sets up data is fine, and so is a
counted loop that repeats one step, whose counter labels at most, as `for (int i = 0; i < MAX_ATTEMPTS; i++)`
exhausting attempts with `"wrong-" + i` does; a counter that picks a case, as `cases[i]` does, still goes through
cases. So is logic in helper methods, in classes a test declares, such as an anonymous fake, and in lambdas that
assert nothing, such as a fake `InvocationHandler`, which run when the code under test calls them.

Why: a test should take one path, so a failure points at one case.

```java
@Test
void addsTwice() {
    Tally tally = new Tally();
    for (int round = 0; round < 2; round++) {
        tally.add(round);
        assertThat(tally.getNext()).isPositive();
    }
}
```

> test TallyTest.addsTwice() has a for loop: a test should take one path
> -> fix: split it into one test per case, or make the cases parameters of a @ParameterizedTest

Fix: one test per case, or a `@ParameterizedTest` with the cases as parameters.

### `tests-fixed-clock`

`tests().testsUseAFixedClock()` · **recommended**

Test code that reads the system clock is reported: `now()` without a `Clock`, `System.currentTimeMillis()`,
`nanoTime()`, `new Date()`, `new GregorianCalendar()`, `Calendar.getInstance()` and the system `Clock`s. A reading
the test overwrites before using it is fine: `Calendar.getInstance()` stored in a variable whose next use is
`clear()`, `setTime(...)` or `setTimeInMillis(...)`. So is a reading the test only prints, logs or ignores, as a
benchmark that prints how long the work took does: the reading, and every value computed from it, through local
variables, the arrays, collections and atomics they fill, and the private methods of the test class they pass
through, ends up only in `System.out`, a logger, or nowhere. A reading that reaches an assertion, a condition, a
field or the code under test counts. Test methods, helpers and field initializers are all checked, in the code tests
run: the classes that declare tests and the test-source classes they use, such as fixtures and sample builders, but
not other code that lives in the test sources, such as a Gatling simulation; fixture code inside strings isn't code.

Why: a test that reads the clock can pass today and fail at midnight, at month end, or in another time zone.

```java
@Test
void recounts() {
    assertThat(java.time.LocalDate.now()).isNotNull();
}
```

> test TallyTest.recounts() reads the system clock through java.time.LocalDate.now()
> -> fix: use a fixed time, such as Instant.parse(...), or pass a fixed Clock, such as Clock.fixed(...), to the code under test

Fix: use a fixed time as test data, such as `Instant.parse("2026-01-01T00:00:00Z")`, and give code that reads the
clock `Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)`.

### `tests-seeded-randomness`

`tests().testsUseSeededRandomness()` · **recommended**

Test code using unseeded randomness is reported: `new Random()`, `new SecureRandom()`, `Math.random()`,
`UUID.randomUUID()`, `ThreadLocalRandom.current()`. A seeded `new Random(42)` is fine, and so is a value the test
only prints, logs or ignores, as for [`tests-fixed-clock`](#tests-fixed-clock).

Why: a test on random data can fail once and never again, and the failing case is gone.

```java
int start = new java.util.Random().nextInt(5);
```

> test TallyTest.waitsForTheTally() uses unseeded randomness through new Random()
> -> fix: use fixed data, or a Random with a fixed seed

Fix: use fixed data, or `new Random(42)`.

### `tests-no-environment`

`tests().testsDontReadTheEnvironment()` · **recommended**

Test code reading environment variables or system properties is reported. A property the test saves to restore is
fine: a variable or field whose every use puts the value back with `System.setProperty(...)` or checks it for null,
as a test that sets `user.home` itself does in its `finally` block or `@AfterEach` method. So is a value the test
only prints, logs or ignores, as for [`tests-fixed-clock`](#tests-fixed-clock).

Why: such a test passes on one machine and fails on another, or in CI.

```java
int variables = System.getenv().size();
```

> test TallyTest.waitsForTheTally() reads the environment through System.getenv()
> -> fix: pass the value in, so the test doesn't depend on the machine it runs on

Fix: pass the value to the code under test, and let the test choose it.

### `tests-no-sleep`

`tests().testsDontSleep()` · **recommended**

Test code that sleeps is reported: `Thread.sleep` and `TimeUnit.X.sleep`.

Why: a sleep long enough on a fast machine is too short on a slow one, and slows every run.

```java
@Test
void waitsForTheTally() throws InterruptedException {
    Thread.sleep(10);
}
```

> test TallyTest.waitsForTheTally() sleeps through Thread.sleep()
> -> fix: wait for the condition instead, for example with Awaitility, or make time injectable

Fix: `await().until(() -> tally.recount() > 0)` with Awaitility, or make time injectable and advance it in the test.
