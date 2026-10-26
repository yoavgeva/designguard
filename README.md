# designguard

[![CI](https://github.com/yoavgeva/designguard/actions/workflows/ci.yml/badge.svg)](https://github.com/yoavgeva/designguard/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.yoavgeva/designguard)](https://central.sonatype.com/artifact/io.github.yoavgeva/designguard)

Design rules for Java, run as plain JUnit tests. They keep code that AI agents write testable, honest and easy to
change, and tell the agent exactly what to fix:

```java
@Test
void design() {
    new DesignGuard("com.shop").recommended().check();
}
```

```
src/main/java/com/shop/Checkout.java:7: error [inject-services] Checkout creates service-like StripeClient with new -> fix: take StripeClient as a constructor parameter, and create it only in wiring code
```

> designguard is at 0.x: rules may still change between minor versions.

- [Why deterministic rules](#why-deterministic-rules)
- [Install](#install)
- [Quick start](#quick-start)
- [Adopting a rule on existing code](#adopting-a-rule-on-existing-code)
- [The rules](#the-rules), each with an example: [Architecture](#architecture) · [Dependencies](#dependencies) ·
  [Types](#types) · [API](#api) · [Guardrails](#guardrails) · [Tests](#tests)
- [Configuration](#configuration) · [Warnings](#warnings) · [Reports for tools](#reports-for-tools) ·
  [Java versions](#java-versions) · [Speed and repeatability](#speed-and-repeatability) · [Build](#build)

## Why deterministic rules

AI agents write code faster than people can review it, and they work until the build is green. The shortcuts that
get there pass every check a project usually has:

- a `TODO` or an `UnsupportedOperationException` where the work should be;
- `@SuppressWarnings` on the warning, `@Disabled` on the test that fails;
- tests that assert nothing, test a mock instead of the class, or sleep and hope;
- services created with `new` and the clock read inline: the code works, and can't be tested.

Prompts, `CLAUDE.md` files and style guides ask for better. An agent follows them most of the time, but not every
time, and less often deep into a long task. An AI reviewer has the same weakness: asked twice about the same diff, it
can answer differently, so it can't block a merge.

designguard's rules are code:

- **The same code gets the same verdict**, on every run and machine, so a rule can block a merge the way a failing
  test does, and recorded violations stay recorded.
- **Every violation is one line saying what to change**: the file and line, the rule, and the fix. An agent running
  the tests reads it and fixes its own code in its loop, before anyone reviews it.
- **Agents copy the patterns they find.** A codebase held to the rules stays good context for the next change, and
  a bad pattern can't spread from one file to fifty.
- **It needs nothing new**: no model, network or API key. The rules read the compiled classes and sources, and run in
  seconds in the test suite the project already has.

Tests check that the code works. designguard checks that it stays testable and changeable, which tests can't see: a
service created with `new` passes every test until someone needs to replace it.

## Install

designguard is a test dependency on Maven Central. It needs Java 17 or later to run, and checks code compiled for
any Java version.

```xml
<dependency>
    <groupId>io.github.yoavgeva</groupId>
    <artifactId>designguard</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

```kotlin
testImplementation("io.github.yoavgeva:designguard:0.1.0")
```

designguard brings its own ASM, so an older one in your build doesn't affect it. ArchUnit, which it builds on, logs
through SLF4J: if your tests have no SLF4J provider, SLF4J prints a three-line notice. A test dependency on
`org.slf4j:slf4j-simple` shows the logs, or `org.slf4j:slf4j-nop` drops them.

## Quick start

Start with the recommended rules. They need no configuration:

```java
import io.github.yoavgeva.designguard.DesignGuard;
import org.junit.jupiter.api.Test;

class DesignTest {

    private static final DesignGuard GUARD = new DesignGuard("com.shop");

    @Test
    void recommended() {
        GUARD.recommended().check();
    }
}
```

`check()` fails with every violation, one line each. The recommended rules cover three kinds of problem:

- **AI shortcuts:** stubs, suppressed warnings, disabled tests, and code widened so tests can reach it.
- **Tests that can't fail or don't repeat:** no assertions, verified queries, mocked values, testing a mock instead
  of the class, logic in tests, the system clock, unseeded randomness, the environment, sleeping.
- **Design faults:** class cycles, queries in loops, work in constructors, accessors that change state, flag
  arguments, choices between strings, misused `Optional`, `int` constants for cases, mutable records, fat interfaces
  and raw threads.

`recommended().rules()` lists their ids, and `without("no-suppressions")` leaves one out. A new designguard version
may add rules.

The other rules are choices, such as an architecture style or a stricter policy, so each runs as a test of its own:

```java
import io.github.yoavgeva.designguard.DesignGuard;
import io.github.yoavgeva.designguard.model.InterfacePolicy;
import org.junit.jupiter.api.Test;

class DesignTest {

    private static final DesignGuard GUARD = new DesignGuard("com.shop");

    @Test
    void hexagonal() {
        GUARD.architecture().hexagonal()
                .adapter("web", "..adapter.web..").adapter("persistence", "..adapter.persistence..")
                .check();
    }

    @Test
    void servicesAreInjected() {
        GUARD.dependencies().injectServices().check();
        GUARD.dependencies().interfacePolicy(InterfacePolicy.PORTS).excluding("..legacy..").check();
    }
}
```

## Adopting a rule on existing code

`frozen(name)` makes a rule fail only on new violations, so a project can adopt it on code that already breaks it:

```java
@Test
void recommended() {
    GUARD.recommended().frozen("recommended").check();
}
```

- The first run records the current violations in `src/test/designguard/frozen/recommended.txt` and fails once, so
  the file gets committed. Until the file exists, the rule keeps failing, so CI catches a record nobody committed.
- Later runs fail only on violations the file doesn't list. A violation is matched by its file, rule and what it's
  about, not its line or the counts in its message, so editing elsewhere keeps it frozen. Each listed violation
  accepts one occurrence.
- Fixed violations drop out of the file, so it only shrinks and a fixed problem can't come back. Commit the change.
- Renaming a class changes its violations' messages, so they show up as new. To accept the current violations again,
  delete the file and run the rule.
- Give each frozen rule its own name: the name is the file's. A rule run against another rule's file fails instead
  of overwriting it. A rule set such as `recommended()` freezes all its rules in one file.

## The rules

Each rule is a method of a category: `GUARD.architecture()`, `dependencies()`, `types()`, `api()`, `guardrails()`,
`data()` and `tests()`. Every rule below shows code it flags, marked `✗`, and under **Fixed** the classes that change to fix
it. A test compiles every example and runs its rule on it, so the examples show what the rules really do. The
examples are in package `shop` unless they say otherwise, leave out imports, and test with JUnit 5, AssertJ and
Mockito.
[docs/rules.md](docs/rules.md) has each rule's full definition, the message it prints, and its options.

The rules use a few terms:

- **Effectful**: code that touches the outside world, directly or through what it calls: I/O, the clock,
  randomness, the environment, threads, a database or HTTP client, or a package given to `treatingAsImpure(...)`.
- **Service-like**: a class that is effectful, holds a service, or is an interface with a service-like
  implementation. Everything else is a **value**.
- **Wiring code**: code that assembles the application, which may create services: classes with a `main` method,
  `@Configuration`, `@SpringBootApplication` and `@Module` classes, and packages given to `wiringIn(...)`.

| | Rule | Flags | Recommended |
|---|---|---|---|
| **Architecture** | [`layered`](#layered) | a layer using a layer above it, or with `strict()` skipping one | |
| | [`mvc`](#mvc) | views or services using anything but models; models using other layers | |
| | [`hexagonal`](#hexagonal) | the domain using anything else; adapters using each other | |
| | [`onion`](#onion) | rings depending outwards | |
| | [`featureSlices`](#featureslices) | a feature using another's internals, or features in a cycle | |
| | [`classesHaveNoCycles`](#classeshavenocycles) | classes that depend on each other, directly or around a loop | ✓ |
| | [`adaptersStayThin`](#adaptersstaythin) | adapter methods, such as controllers, making more than two decisions | |
| | [`frameworksStayOutOf`](#frameworksstayoutof) | Spring, Jakarta EE, Hibernate, Jackson, Guice... in the domain | |
| | [`infrastructureExceptionsStayOutOf`](#infrastructureexceptionsstayoutof) | `SQLException`, `IOException` or JPA exceptions in the domain | |
| | [`entitiesStayOutOf`](#entitiesstayoutof) | persistence entities used past the persistence layer | |
| **Dependencies** | [`interfacePolicy`](#interfacepolicy) | concrete dependencies where an interface is required | |
| | [`injectServices`](#injectservices) | services created with `new`, reached through a singleton or looked up, outside wiring code | |
| | [`noQueriesInLoops`](#noqueriesinloops) | a query made once per item, in a loop or a stream: N+1 | ✓ |
| | [`noIoInTransactions`](#noiointransactions) | HTTP, messaging or mail inside a `@Transactional` method | ✓ |
| | [`constructorsDoNoWork`](#constructorsdonowork) | constructors doing I/O or calling effectful code | ✓ |
| | [`clockIsInjected`](#clockisinjected) | `LocalDate.now()`, `System.currentTimeMillis()`... instead of an injected `Clock` | |
| | [`randomnessIsInjected`](#randomnessisinjected) | `new Random()`, `UUID.randomUUID()`... | |
| | [`configurationIsInjected`](#configurationisinjected) | `System.getenv()`, `System.getProperty()`... | |
| | [`noRawThreads`](#norawthreads) | `new Thread(...)` instead of an executor | ✓ |
| | [`stableDependencies`](#stabledependencies) | a slice depending on a less stable slice | |
| **Types** | [`typeDispatch`](#typedispatch) | `instanceof` chains and switches on open hierarchies, `default` branches hiding new cases | |
| | [`recordsAreImmutable`](#recordsareimmutable) | record components callers can still change | ✓ |
| | [`dataClassesAreRecords`](#dataclassesarerecords) | classes that only carry data | |
| | [`valueTypesAreImmutable`](#valuetypesareimmutable) | values with non-final fields | |
| | [`servicesAreStateless`](#servicesarestateless) | service-like classes holding changing state | |
| | [`typedIdentifiers`](#typedidentifiers) | identifiers like `String customerId` instead of their own types | |
| | [`enumsOverConstants`](#enumsoverconstants) | int or String constants enumerating cases | ✓ |
| | [`interfacesAreFocused`](#interfacesarefocused) | interfaces of 10+ methods whose clients each use at most half | ✓ |
| | [`closedHierarchiesAreSealed`](#closedhierarchiesaresealed) | interfaces implemented only in the project but not sealed | |
| | [`inheritance`](#inheritance) | non-final leaves, extending concrete classes, deep hierarchies | |
| **API** | [`noFlagArguments`](#noflagarguments) | boolean parameters that pick between behaviours | ✓ |
| | [`packagesAreNullMarked`](#packagesarenullmarked) | packages without JSpecify's `@NullMarked` | |
| | [`optionalUsage`](#optionalusage) | `Optional` fields and parameters, `return null` from an `Optional` method | ✓ |
| | [`noCheckedExceptions`](#nocheckedexceptions) | checked exception classes and declarations | |
| | [`noEffectfulStaticMethods`](#noeffectfulstaticmethods) | static methods with effects that callers can't replace in tests | |
| | [`typedApis`](#typedapis) | public methods taking or returning `Object`, `Map<String, Object>` or JSON trees | |
| | [`noStringlyTypedChoices`](#nostringlytypedchoices) | choices between three or more string literals | ✓ |
| | [`accessorsChangeNothing`](#accessorschangenothing) | `get`, `is` and `has` methods that change state | ✓ |
| | [`minimalVisibility`](#minimalvisibility) | public types only their own package uses (applications) | |
| **Guardrails** | [`noStubs`](#nostubs) | `TODO`/`FIXME`, "not implemented" throws, empty methods | ✓ |
| | [`noSuppressions`](#nosuppressions) | `@SuppressWarnings`, `NOPMD`, `NOSONAR`, `noinspection`, `CHECKSTYLE:OFF` | ✓ |
| | [`noWidenedForTests`](#nowidenedfortests) | package-private or protected methods only tests call | ✓ |
| | [`serviceLikeClassesHaveTests`](#servicelikeclasseshavetests) | service-like classes without a `<Name>Test` | |
| | [`noUnusedPublicCode`](#nounusedpubliccode) | public types and methods nothing uses (applications) | |
| | [`noSwallowedErrors`](#noswallowederrors) | catch-alls that log and return `null`, fallbacks for every error | ✓ |
| | [`writesAreAuthorized`](#writesareauthorized) | request handlers that write with no permission check or current user | |
| | [`readsAreAuthorized`](#readsareauthorized) | request handlers that read a record by the request's id with no check | |
| | [`noUnboundedWaits`](#nounboundedwaits) | `Future.get()`, `block()` and HTTP clients without a timeout | ✓ |
| **Data** | [`noMassAssignment`](#nomassassignment) | handlers that save the request's object with its `role`, `userId` or other privileged field | ✓ |
| | [`noLostUpdates`](#nolostupdates) | entities set from their old values with no `@Version` or lock | ✓ |
| | [`noFloatingPointMoney`](#nofloatingpointmoney) | `double` and `float` fields named for money | ✓ |
| | [`noUnboundedQueries`](#nounboundedqueries) | request handlers that reach `findAll()` on a whole table | |
| **Tests** | [`testsAssertMeaningfully`](#testsassertmeaningfully) | tests asserting nothing, on constants, or a value against itself | ✓ |
| | [`noDisabledTests`](#nodisabledtests) | `@Disabled`, `@Ignore`, `@Test(enabled = false)` | ✓ |
| | [`testsCheckResults`](#testscheckresults) | `verify(mock).query()` on methods that return a value | ✓ |
| | [`testsUseTheirSubject`](#testsusetheirsubject) | `CheckoutTest` never using `Checkout`, or only mocking it | ✓ |
| | [`testsDontMockValues`](#testsdontmockvalues) | mocks of records, enums and other values | ✓ |
| | [`testsHaveNoLogic`](#testshavenologic) | `if` or `switch` in tests, or loops around assertions | ✓ |
| | [`testsUseAFixedClock`](#testsuseafixedclock) | tests reading the system clock | ✓ |
| | [`testsUseSeededRandomness`](#testsuseseededrandomness) | tests using unseeded randomness | ✓ |
| | [`testsDontReadTheEnvironment`](#testsdontreadtheenvironment) | tests reading environment variables or system properties | ✓ |
| | [`testsDontSleep`](#testsdontsleep) | tests calling `Thread.sleep` | ✓ |

### Architecture

#### `layered`

`GUARD.architecture().layered().layer("Web", "..web..").layer("Persistence", "..persistence..")` · `layered-architecture`

Layers are listed top to bottom, and each may use only the layers below it; with `.strict()`, only the one directly
below. A lower layer that reaches up can't change, be tested or be reused without the layers above it.

```java
package shop.web;

public class OrderController {
    public void refresh(String orderId) {
        System.out.println("order " + orderId + " changed");
    }
}
```

```java
package shop.persistence;

public class OrderRepository {
    private final OrderController controller;   // ✗ Persistence uses Web, a layer above it

    public OrderRepository(OrderController controller) {
        this.controller = controller;
    }

    public void save(String orderId) {
        controller.refresh(orderId);
    }
}
```

<details><summary>Fixed</summary>

Persistence owns an interface for what it needs, and Web implements it.

```java
package shop.persistence;

public interface OrderEvents {
    void changed(String orderId);
}

public class OrderRepository {
    private final OrderEvents events;

    public OrderRepository(OrderEvents events) {
        this.events = events;
    }

    public void save(String orderId) {
        events.changed(orderId);
    }
}
```

```java
package shop.web;

public class OrderController implements OrderEvents {
    @Override
    public void changed(String orderId) {
        System.out.println("order " + orderId + " changed");
    }
}
```

</details>

#### `mvc`

`GUARD.architecture().mvc()` · `mvc-architecture`

Controllers may use views, services and models; views and services may use models only; models use no other layer.
The layers are `..controller..`, `..view..`, `..service..` and `..model..`, or what `.controllers(...)`,
`.views(...)`, `.services(...)` and `.models(...)` say.

```java
package shop.service;

public class Pricing {
    public long withTax(long cents) {
        return cents * 120 / 100;
    }
}
```

```java
package shop.model;

public record Invoice(long cents) {
    public long total(Pricing pricing) {   // ✗ a model using a service
        return pricing.withTax(cents);
    }
}
```

<details><summary>Fixed</summary>

The service uses the model, not the other way round.

```java
package shop.model;

public record Invoice(long cents) {
}
```

```java
package shop.service;

public class Pricing {
    public long total(Invoice invoice) {
        return invoice.cents() * 120 / 100;
    }
}
```

</details>

#### `hexagonal`

`GUARD.architecture().hexagonal().adapter("web", "..web..")` · `hexagonal-architecture`

The domain depends on nothing else, the application only on the domain, and each adapter on the application and the
domain, never on another adapter. The domain and application are `..domain..` and `..application..`, or what
`.domain(...)` and `.application(...)` say.

```java
package shop.web;

public class OrderPage {
    public String render(String item) {
        return "<p>" + item + "</p>";
    }
}
```

```java
package shop.application;

public class PlaceOrder {
    private final OrderPage page;   // ✗ the application depends on the web adapter

    public PlaceOrder(OrderPage page) {
        this.page = page;
    }

    public String place(String item) {
        return page.render(item.strip());
    }
}
```

<details><summary>Fixed</summary>

The adapter calls the application, and renders what it returns.

```java
package shop.application;

public class PlaceOrder {
    public String place(String item) {
        return item.strip();
    }
}
```

```java
package shop.web;

public class OrderPage {
    private final PlaceOrder placeOrder;

    public OrderPage(PlaceOrder placeOrder) {
        this.placeOrder = placeOrder;
    }

    public String submit(String item) {
        return "<p>" + placeOrder.place(item) + "</p>";
    }
}
```

</details>

#### `onion`

`GUARD.architecture().onion()` · `onion-architecture`

Rings depend only inwards: the domain model, then domain services, then application services, then adapters, which
never depend on each other. The rings are `..domain.model..`, `..domain.service..` and `..application..`, and
adapters are declared with `.adapter(name, packages)`.

```java
package shop.domain.service;

public class Pricing {
    public long price(String item) {
        return item.length() * 100L;
    }
}
```

```java
package shop.domain.model;

public record Order(String item) {
    public long total(Pricing pricing) {   // ✗ the domain model depends on an outer ring
        return pricing.price(item);
    }
}
```

<details><summary>Fixed</summary>

```java
package shop.domain.model;

public record Order(String item) {
}
```

```java
package shop.domain.service;

public class Pricing {
    public long total(Order order) {
        return order.item().length() * 100L;
    }
}
```

</details>

#### `featureSlices`

`GUARD.architecture().featureSlices()` · `feature-slices`

Package by feature: each package directly below the checked one is a feature. Its own package is its API, and its
subpackages are internals no other feature may use. Features depending on each other in a cycle are flagged too.

```java
package shop.orders.internal;

public class OrderMath {
    public long total(long cents, int quantity) {
        return cents * quantity;
    }
}
```

```java
package shop.billing;

public class Biller {
    public long bill(long cents, int quantity) {
        return new OrderMath().total(cents, quantity);   // ✗ another feature's internals
    }
}
```

<details><summary>Fixed</summary>

The orders feature offers what billing needs in its API package.

```java
package shop.orders;

public class Orders {
    public long total(long cents, int quantity) {
        return new OrderMath().total(cents, quantity);
    }
}
```

```java
package shop.billing;

public class Biller {
    public long bill(long cents, int quantity) {
        return new Orders().total(cents, quantity);
    }
}
```

</details>

#### `classesHaveNoCycles`

`GUARD.architecture().classesHaveNoCycles()` · `class-cycles` · recommended

Classes that depend on each other, directly or around a loop, can only be understood, tested and changed together.

```java
public class Customer {
    private Order lastOrder;

    public void ordered(Order order) {
        lastOrder = order;
    }
}

public class Order {
    private final Customer customer;   // ✗ Order and Customer depend on each other

    public Order(Customer customer) {
        this.customer = customer;
    }
}
```

<details><summary>Fixed</summary>

The order refers to its customer by name, so only `Customer` depends on `Order`.

```java
public record Order(String customerName) {
}
```

</details>

#### `adaptersStayThin`

`GUARD.architecture().adaptersStayThin("..web..")` · `thin-adapters`

Methods in adapter packages, such as web controllers or message listeners, may make two decisions at most: `if`s,
ternaries, `switch` cases and loops. Decisions in an adapter can only be tested through HTTP or a broker.

```java
package shop.web;

public class CheckoutPage {
    public String show(int items, boolean member) {
        if (items == 0) {
            return "Your cart is empty";
        }
        if (member) {
            return "Free shipping";
        }
        return items > 10 ? "Bulk discount" : "Standard shipping";   // ✗ a third decision
    }
}
```

<details><summary>Fixed</summary>

The decisions move into an application class, which plain unit tests reach.

```java
public class Shipping {
    public String offer(int items, boolean member) {
        if (items == 0) {
            return "Your cart is empty";
        }
        if (member) {
            return "Free shipping";
        }
        return items > 10 ? "Bulk discount" : "Standard shipping";
    }
}
```

```java
package shop.web;

public class CheckoutPage {
    private final Shipping shipping;

    public CheckoutPage(Shipping shipping) {
        this.shipping = shipping;
    }

    public String show(int items, boolean member) {
        return shipping.offer(items, member);
    }
}
```

</details>

#### `frameworksStayOutOf`

`GUARD.architecture().frameworksStayOutOf("..domain..")` · `frameworks-stay-out`

The domain stays plain Java, without Spring, Jakarta EE, Hibernate, Jackson, Gson, Guice, Dagger, Micronaut, Quarkus
or Vert.x types and annotations, so it outlives framework upgrades and tests without a container.

```java
package shop.domain;

import org.springframework.stereotype.Service;

@Service   // ✗ Spring in the domain
public class Pricing {
    public long price(long cents, int quantity) {
        return cents * quantity;
    }
}
```

<details><summary>Fixed</summary>

The domain class is plain, and wiring code or an adapter creates it.

```java
package shop.domain;

public class Pricing {
    public long price(long cents, int quantity) {
        return cents * quantity;
    }
}
```

</details>

#### `infrastructureExceptionsStayOutOf`

`GUARD.architecture().infrastructureExceptionsStayOutOf("..domain..")` · `domain-exceptions`

The domain doesn't name JDBC, I/O, JPA, Spring data access, Jackson, Kafka or AWS exceptions, which tie it to a
technology and make callers handle storage details.

```java
package shop.domain;

public interface OrderStore {
    String load(String orderId) throws SQLException;   // ✗ a database exception in the domain
}
```

<details><summary>Fixed</summary>

The domain declares its own exception. The adapter implementing `OrderStore` catches the `SQLException` and throws
it.

```java
package shop.domain;

public class OrderNotFound extends RuntimeException {
    public OrderNotFound(String orderId) {
        super(orderId);
    }
}

public interface OrderStore {
    String load(String orderId);
}
```

</details>

#### `entitiesStayOutOf`

`GUARD.architecture().entitiesStayOutOf("..web..")` · `entities-stay-behind`

Persistence entities (`@Entity`, `@Embeddable`, `@MappedSuperclass`) stay behind the persistence layer. An entity in
an API couples its clients to the database schema, and lazy loading leaks out with it.

```java
package shop.persistence;

import jakarta.persistence.Entity;

@Entity
public class OrderEntity {
    private String item;

    public String getItem() {
        return item;
    }
}
```

```java
package shop.web;

public class OrderPage {
    public String render(OrderEntity order) {   // ✗ the entity leaves the persistence layer
        return "<p>" + order.getItem() + "</p>";
    }
}
```

<details><summary>Fixed</summary>

The persistence layer maps the entity to a record, and the web layer renders that.

```java
public record OrderView(String item) {
}
```

```java
package shop.persistence;

import jakarta.persistence.Entity;

@Entity
public class OrderEntity {
    private String item;

    public OrderView toView() {
        return new OrderView(item);
    }
}
```

```java
package shop.web;

public class OrderPage {
    public String render(OrderView order) {
        return "<p>" + order.item() + "</p>";
    }
}
```

</details>

### Dependencies

#### `interfacePolicy`

`GUARD.dependencies().interfacePolicy(InterfacePolicy.SERVICES)` · `interface-policy`

Depending on a concrete class where the policy wants an interface is flagged. `PORTS` wants one for service-like
classes used across slices, `SERVICES` for every service-like class, and `ALL` for every project class. With an
interface, tests pass a fake and the implementation can change without touching callers.

```java
public class ReceiptPrinter {
    public void print(String receipt) throws IOException {
        Files.writeString(Path.of("receipt.txt"), receipt);
    }
}

public class Checkout {
    private final ReceiptPrinter printer;   // ✗ a concrete class that does I/O

    public Checkout(ReceiptPrinter printer) {
        this.printer = printer;
    }

    public void pay(String order) throws IOException {
        printer.print(order);
    }
}
```

<details><summary>Fixed</summary>

```java
public interface Receipts {
    void print(String receipt) throws IOException;
}

public class ReceiptPrinter implements Receipts {
    @Override
    public void print(String receipt) throws IOException {
        Files.writeString(Path.of("receipt.txt"), receipt);
    }
}

public class Checkout {
    private final Receipts receipts;

    public Checkout(Receipts receipts) {
        this.receipts = receipts;
    }

    public void pay(String order) throws IOException {
        receipts.print(order);
    }
}
```

</details>

#### `injectServices`

`GUARD.dependencies().injectServices()` · `inject-services`

Outside wiring code, services are passed in, not obtained: an effectful class created with `new`, a service reached
through a static singleton, or one looked up with Spring's `getBean`, `ServiceLoader`, JNDI, Guice or CDI is flagged.
A class that creates its own services can't be tested without them.

```java
public class ReceiptPrinter {
    public void print(String receipt) throws IOException {
        Files.writeString(Path.of("receipt.txt"), receipt);
    }
}

public class Checkout {
    private final ReceiptPrinter printer = new ReceiptPrinter();   // ✗ creates the service it uses

    public void pay(String order) throws IOException {
        printer.print(order);
    }
}
```

<details><summary>Fixed</summary>

`Checkout` takes the printer, and wiring code creates it.

```java
public class Checkout {
    private final ReceiptPrinter printer;

    public Checkout(ReceiptPrinter printer) {
        this.printer = printer;
    }

    public void pay(String order) throws IOException {
        printer.print(order);
    }
}

public class Main {
    public static void main(String[] args) throws IOException {
        new Checkout(new ReceiptPrinter()).pay("book");
    }
}
```

</details>

#### `noQueriesInLoops`

`GUARD.dependencies().noQueriesInLoops()` · `no-queries-in-loops` · recommended

A query made once per item, in a loop or a stream, turns a page of 50 orders into 51 database or HTTP calls: the
N+1 problem. A query is a call to a collaborator that reaches a database or another machine and returns a value.

```java
public class PriceRepository {
    private final Connection db;

    public PriceRepository(Connection db) {
        this.db = db;
    }

    public long priceOf(String item) throws SQLException {
        PreparedStatement query = db.prepareStatement("select cents from prices where item = ?");
        query.setString(1, item);
        ResultSet row = query.executeQuery();
        return row.next() ? row.getLong(1) : 0;
    }
}

public class Checkout {
    private final PriceRepository prices;

    public Checkout(PriceRepository prices) {
        this.prices = prices;
    }

    public long total(List<String> items) throws SQLException {
        long total = 0;
        for (String item : items) {
            total += prices.priceOf(item);   // ✗ one query per item
        }
        return total;
    }
}
```

<details><summary>Fixed</summary>

One query loads the prices of all the items.

```java
public class PriceRepository {
    private final Connection db;

    public PriceRepository(Connection db) {
        this.db = db;
    }

    public Map<String, Long> pricesOf(List<String> items) throws SQLException {
        PreparedStatement query = db.prepareStatement("select item, cents from prices where item = any(?)");
        query.setArray(1, db.createArrayOf("varchar", items.toArray()));
        ResultSet rows = query.executeQuery();
        Map<String, Long> prices = new HashMap<>();
        while (rows.next()) {
            prices.put(rows.getString(1), rows.getLong(2));
        }
        return prices;
    }
}

public class Checkout {
    private final PriceRepository prices;

    public Checkout(PriceRepository prices) {
        this.prices = prices;
    }

    public long total(List<String> items) throws SQLException {
        Map<String, Long> found = prices.pricesOf(items);
        return items.stream().mapToLong(item -> found.getOrDefault(item, 0L)).sum();
    }
}
```

</details>


#### `noIoInTransactions`

`GUARD.dependencies().noIoInTransactions()` · `no-io-in-transactions` · recommended

A call to another system inside a transaction can't be rolled back: the customer is charged for an order that never
saved. And the transaction holds its database connection while the other side answers, so a slow API drains the pool.

```java
import org.springframework.transaction.annotation.Transactional;

public class Orders {
    private final Connection db;
    private final HttpClient http;

    public Orders(Connection db, HttpClient http) {
        this.db = db;
        this.http = http;
    }

    @Transactional
    public void place(String order) throws SQLException, IOException, InterruptedException {
        PreparedStatement insert = db.prepareStatement("insert into orders (id) values (?)");
        insert.setString(1, order);
        insert.executeUpdate();
        http.send(charge(order), HttpResponse.BodyHandlers.discarding());   // ✗ charges before the order commits
    }

    private static HttpRequest charge(String order) {
        return HttpRequest.newBuilder(URI.create("https://pay.example.com/charges/" + order))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }
}
```

<details><summary>Fixed</summary>

The transaction saves the order; the charge goes out after it commits.

```java
import org.springframework.transaction.annotation.Transactional;

public class OrderStore {
    private final Connection db;

    public OrderStore(Connection db) {
        this.db = db;
    }

    @Transactional
    public void save(String order) throws SQLException {
        PreparedStatement insert = db.prepareStatement("insert into orders (id) values (?)");
        insert.setString(1, order);
        insert.executeUpdate();
    }
}

public class Orders {
    private final OrderStore store;
    private final HttpClient http;

    public Orders(OrderStore store, HttpClient http) {
        this.store = store;
        this.http = http;
    }

    public void place(String order) throws SQLException, IOException, InterruptedException {
        store.save(order);
        http.send(charge(order), HttpResponse.BodyHandlers.discarding());
    }

    private static HttpRequest charge(String order) {
        return HttpRequest.newBuilder(URI.create("https://pay.example.com/charges/" + order))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }
}
```

</details>
#### `constructorsDoNoWork`

`GUARD.dependencies().constructorsDoNoWork()` · `constructors-do-no-work` · recommended

Constructors and static initializers only store what they get. One that does I/O, prints, starts threads or calls a
collaborator that does can't be created cheaply in a test, and a failure there leaves no object to recover with.

```java
public class PriceList {
    private final List<String> lines;

    public PriceList(Path file) throws IOException {
        this.lines = Files.readAllLines(file);   // ✗ reads a file while being created
    }

    public int size() {
        return lines.size();
    }
}
```

<details><summary>Fixed</summary>

The constructor takes the lines, and wiring code reads the file.

```java
public class PriceList {
    private final List<String> lines;

    public PriceList(List<String> lines) {
        this.lines = List.copyOf(lines);
    }

    public int size() {
        return lines.size();
    }
}

public class Main {
    public static void main(String[] args) throws IOException {
        PriceList prices = new PriceList(Files.readAllLines(Path.of("prices.txt")));
        System.out.println(prices.size() + " prices");
    }
}
```

</details>

#### `clockIsInjected`

`GUARD.dependencies().clockIsInjected()` · `inject-clock`

Code that reads the system clock (`now()` without a `Clock`, `System.currentTimeMillis()`, `new Date()`...) gives a
different result on every run, so its tests can't pin the time.

```java
public class Invoice {
    private final LocalDate due;

    public Invoice(LocalDate due) {
        this.due = due;
    }

    public boolean overdue() {
        return LocalDate.now().isAfter(due);   // ✗ reads the system clock
    }
}
```

<details><summary>Fixed</summary>

The invoice takes a `Clock`, and tests pass `Clock.fixed(...)`.

```java
public class Invoice {
    private final LocalDate due;
    private final Clock clock;

    public Invoice(LocalDate due, Clock clock) {
        this.due = due;
        this.clock = clock;
    }

    public boolean overdue() {
        return LocalDate.now(clock).isAfter(due);
    }
}
```

</details>

#### `randomnessIsInjected`

`GUARD.dependencies().randomnessIsInjected()` · `inject-randomness`

Code that creates randomness (`new Random()`, `UUID.randomUUID()`, `Math.random()`...) can't be asserted on, and a
failing case may never come back.

```java
public class OrderNumbers {
    public String next() {
        return "order-" + UUID.randomUUID();   // ✗ tests can't know what comes out
    }
}
```

<details><summary>Fixed</summary>

The class takes a source of IDs. Wiring code passes `UUID::randomUUID`, and tests a fixed one.

```java
public class OrderNumbers {
    private final Supplier<UUID> ids;

    public OrderNumbers(Supplier<UUID> ids) {
        this.ids = ids;
    }

    public String next() {
        return "order-" + ids.get();
    }
}
```

</details>

#### `configurationIsInjected`

`GUARD.dependencies().configurationIsInjected()` · `inject-configuration`

Code that reads environment variables or system properties hides its configuration from callers, and tests can't
vary it.

```java
public class Shipping {
    public long cost(long cents) {
        String region = System.getenv("REGION");   // ✗ configuration read deep in the code
        return "EU".equals(region) ? cents / 10 : cents / 5;
    }
}
```

<details><summary>Fixed</summary>

Wiring code reads the variable once and passes the value in.

```java
public class Shipping {
    private final String region;

    public Shipping(String region) {
        this.region = region;
    }

    public long cost(long cents) {
        return "EU".equals(region) ? cents / 10 : cents / 5;
    }
}
```

</details>

#### `noRawThreads`

`GUARD.dependencies().noRawThreads()` · `no-raw-threads` · recommended

Threads started by hand aren't pooled, bounded or shut down, and tests can't run their work synchronously.

```java
public class Mailer {
    public void sendLater(Runnable send) {
        new Thread(send).start();   // ✗ an unpooled, unbounded thread
    }
}
```

<details><summary>Fixed</summary>

The work goes to an injected executor. Tests pass `Runnable::run` to run it at once.

```java
public class Mailer {
    private final Executor executor;

    public Mailer(Executor executor) {
        this.executor = executor;
    }

    public void sendLater(Runnable send) {
        executor.execute(send);
    }
}
```

</details>

#### `stableDependencies`

`GUARD.dependencies().stableDependencies()` · `stable-dependencies`

Depend in the direction of stability. A slice that many others use is hard to change, so it shouldn't depend on one
that changes more easily. A slice's instability is `I = Ce / (Ca + Ce)`: `Ce` counts its classes that use other
slices, and `Ca` other slices' classes that use it.

```java
package shop.core;

public record Money(long cents) {
    public String show() {
        return Format.cents(cents);   // ✗ core, which others use, depends on reports
    }
}
```

```java
package shop.reports;

public final class Format {
    private Format() {
    }

    public static String cents(long cents) {
        return String.format("%d.%02d", cents / 100, cents % 100);
    }
}

public class Report {
    public String line(Money total) {
        return "Total: " + total.show();
    }
}
```

```java
package shop.billing;

public class Biller {
    public Money total(long cents, int quantity) {
        return new Money(cents * quantity);
    }
}
```

<details><summary>Fixed</summary>

What `core` needs moves into `core`, which then depends on nothing.

```java
package shop.core;

public record Money(long cents) {
    public String show() {
        return String.format("%d.%02d", cents / 100, cents % 100);
    }
}
```

</details>

### Types

#### `typeDispatch`

`GUARD.types().typeDispatch(TypeDispatchPolicy.SEALED_ONLY)` · `type-dispatch`

Branching on the kind of a value breaks silently when a new kind is added. Flagged are `instanceof` chains, switches
on types that aren't sealed (with `SEALED_ONLY`), `default` branches over sealed types and enums, and a type branched
on in more places than `.maxPlacesPerType(n)` allows.

```java
public interface Shape {
}

public record Circle(double radius) implements Shape {
}

public record Square(double side) implements Shape {
}

public class Geometry {
    public double area(Shape shape) {
        if (shape instanceof Circle circle) {   // ✗ a new kind of Shape slips through
            return Math.PI * circle.radius() * circle.radius();
        } else if (shape instanceof Square square) {
            return square.side() * square.side();
        }
        throw new IllegalArgumentException("unknown shape " + shape);
    }
}
```

<details><summary>Fixed</summary>

Each shape computes its own area, and a new shape won't compile without one. On Java 21, an exhaustive `switch` over a
sealed `Shape` works too.

```java
public sealed interface Shape permits Circle, Square {
    double area();
}

public record Circle(double radius) implements Shape {
    @Override
    public double area() {
        return Math.PI * radius * radius;
    }
}

public record Square(double side) implements Shape {
    @Override
    public double area() {
        return side * side;
    }
}

public class Geometry {
    public double area(Shape shape) {
        return shape.area();
    }
}
```

</details>

#### `recordsAreImmutable`

`GUARD.types().recordsAreImmutable()` · `immutable-records` · recommended

A record is a value only if it can't change. A list the caller still holds changes the record, and its equality,
behind its back. Flagged are uncopied collections, arrays, mutable JDK types like `Date`, and the project's mutable
classes.

```java
public record Basket(List<String> items) {   // ✗ the caller can still change items
}
```

<details><summary>Fixed</summary>

```java
public record Basket(List<String> items) {
    public Basket {
        items = List.copyOf(items);
    }
}
```

</details>

#### `dataClassesAreRecords`

`GUARD.types().dataClassesAreRecords()` · `data-classes-are-records`

A class that only carries data, with `private final` fields and nothing but accessors, `equals`, `hashCode` and
`toString`, says so better as a record, and gets those methods right for free. Needs code that targets Java 16.

```java
public final class Point {   // ✗ only carries data
    private final int x;
    private final int y;

    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }
}
```

<details><summary>Fixed</summary>

```java
public record Point(int x, int y) {
}
```

</details>

#### `valueTypesAreImmutable`

`GUARD.types().valueTypesAreImmutable()` · `immutable-values`

A value with a non-final field can't be shared or used as a map key, and changes behind its holders' backs.

```java
public class Tally {
    private int total;   // ✗ a value that changes

    public void add(int amount) {
        total += amount;
    }

    public int total() {
        return total;
    }
}
```

<details><summary>Fixed</summary>

Adding returns a new tally.

```java
public record Tally(int total) {
    public Tally add(int amount) {
        return new Tally(total + amount);
    }
}
```

</details>

#### `servicesAreStateless`

`GUARD.types().servicesAreStateless()` · `stateless-services`

A service is shared by its callers, so state it holds leaks between them and races under concurrency. Flagged are
non-final fields of service-like classes, and final ones holding mutable collections, atomics or builders.

```java
public class ReceiptPrinter {
    private int printed;   // ✗ state shared by every caller

    public void print(String receipt) throws IOException {
        printed++;
        Files.writeString(Path.of("receipt-" + printed + ".txt"), receipt);
    }
}
```

<details><summary>Fixed</summary>

The caller passes the receipt's number in.

```java
public class ReceiptPrinter {
    public void print(int number, String receipt) throws IOException {
        Files.writeString(Path.of("receipt-" + number + ".txt"), receipt);
    }
}
```

</details>

#### `typedIdentifiers`

`GUARD.types().typedIdentifiers("..domain..")` · `typed-identifiers`

Identifiers in the domain get their own types, so the compiler stops a customer's ID from going where a product's
belongs. Flagged are fields, record components and parameters named `id` or `somethingId` typed as strings, numbers
or UUIDs.

```java
package shop.domain;

public record Order(String customerId, String productId, int quantity) {   // ✗ easy to swap
}
```

<details><summary>Fixed</summary>

```java
package shop.domain;

public record CustomerId(String value) {
}

public record ProductId(String value) {
}

public record Order(CustomerId customerId, ProductId productId, int quantity) {
}
```

</details>

#### `enumsOverConstants`

`GUARD.types().enumsOverConstants()` · `enums-over-constants` · recommended

Constants that enumerate cases, like `STATUS_OPEN = 0` and `STATUS_PAID = 1`, accept any number, and the compiler
can't tell when a `switch` misses one. Constants with real values, like `MAX_ITEMS = 50`, are fine.

```java
public class Order {
    public static final int STATUS_OPEN = 0;   // ✗ cases as int constants
    public static final int STATUS_PAID = 1;
    public static final int STATUS_SHIPPED = 2;

    private final int status;

    public Order(int status) {
        this.status = status;
    }

    public boolean paid() {
        return status == STATUS_PAID;
    }
}
```

<details><summary>Fixed</summary>

```java
public enum Status {
    OPEN, PAID, SHIPPED
}

public record Order(Status status) {
    public boolean paid() {
        return status == Status.PAID;
    }
}
```

</details>

#### `interfacesAreFocused`

`GUARD.types().interfacesAreFocused()` · `focused-interfaces` · recommended

An interface of ten or more methods, whose two or more clients each use at most half of them, makes every client
depend on methods it never calls, and every implementation, fakes included, provide them all.

```java
public interface Store {   // ✗ each page uses a sliver of it
    void saveOrder(String order);
    String loadOrder(String id);
    void deleteOrder(String id);
    List<String> listOrders();
    int countOrders();
    void saveCustomer(String customer);
    String loadCustomer(String id);
    void deleteCustomer(String id);
    List<String> listCustomers();
    int countCustomers();
}

public class OrderPage {
    private final Store store;

    public OrderPage(Store store) {
        this.store = store;
    }

    public String show(String id) {
        return store.loadOrder(id);
    }
}

public class CustomerPage {
    private final Store store;

    public CustomerPage(Store store) {
        this.store = store;
    }

    public String show(String id) {
        return store.loadCustomer(id);
    }
}
```

<details><summary>Fixed</summary>

One interface per role. A class may still implement both.

```java
public interface OrderStore {
    void saveOrder(String order);
    String loadOrder(String id);
    void deleteOrder(String id);
    List<String> listOrders();
    int countOrders();
}

public interface CustomerStore {
    void saveCustomer(String customer);
    String loadCustomer(String id);
    void deleteCustomer(String id);
    List<String> listCustomers();
    int countCustomers();
}

public class OrderPage {
    private final OrderStore store;

    public OrderPage(OrderStore store) {
        this.store = store;
    }

    public String show(String id) {
        return store.loadOrder(id);
    }
}

public class CustomerPage {
    private final CustomerStore store;

    public CustomerPage(CustomerStore store) {
        this.store = store;
    }

    public String show(String id) {
        return store.loadCustomer(id);
    }
}
```

</details>

#### `closedHierarchiesAreSealed`

`GUARD.types().closedHierarchiesAreSealed()` · `sealed-hierarchies`

An interface only the project implements can be sealed, and then the compiler checks every `switch` over it for
missing cases. Meant for applications, since a library's users may implement its interfaces. Needs code that targets
Java 17.

```java
public interface Payment {   // ✗ only implemented here, but not sealed
}

public record Card(String number) implements Payment {
}

public record Voucher(String code) implements Payment {
}
```

<details><summary>Fixed</summary>

```java
public sealed interface Payment permits Card, Voucher {
}
```

</details>

#### `inheritance`

`GUARD.types().inheritance(InheritancePolicy.FINAL_BY_DEFAULT)` · `inheritance`

Composition over inheritance. With `FINAL_BY_DEFAULT`, a concrete class nothing extends must be final; with
`ABSTRACT_ONLY`, classes may extend abstract classes only; `.maxDepth(n)` limits how deep hierarchies go.

```java
public class Discount {   // ✗ open to subclassing it wasn't designed for
    public long apply(long cents) {
        return cents * 9 / 10;
    }
}
```

<details><summary>Fixed</summary>

```java
public final class Discount {
    public long apply(long cents) {
        return cents * 9 / 10;
    }
}
```

</details>

### API

#### `noFlagArguments`

`GUARD.api().noFlagArguments()` · `no-flag-arguments` · recommended

A boolean parameter that picks what a method does means the method does two things, and a call like
`render(text, true)` doesn't say which.

```java
public class Receipt {
    public String render(String text, boolean html) {   // ✗ what does true mean at the call?
        if (html) {
            return "<p>" + text + "</p>";
        }
        return text.strip();
    }
}
```

<details><summary>Fixed</summary>

```java
public class Receipt {
    public String renderHtml(String text) {
        return "<p>" + text + "</p>";
    }

    public String renderText(String text) {
        return text.strip();
    }
}
```

</details>

#### `packagesAreNullMarked`

`GUARD.api().packagesAreNullMarked()` · `null-marked`

Packages carry JSpecify's `@NullMarked`, so every type in them is non-null unless marked `@Nullable`, and checkers
like NullAway can verify it.

```java
public record Customer(String name, String nickname) {   // ✗ may nickname be null? The package doesn't say
}
```

<details><summary>Fixed</summary>

```java
// package-info.java
@NullMarked
package shop;

import org.jspecify.annotations.NullMarked;
```

```java
import org.jspecify.annotations.Nullable;

public record Customer(String name, @Nullable String nickname) {
}
```

</details>

#### `optionalUsage`

`GUARD.api().optionalUsage()` · `optional-usage` · recommended

`Optional` is a return type saying "there may be no result". As a field or a parameter it adds a third state, a null
`Optional`, and `return null` from an `Optional` method defeats it.

```java
public class Greeter {
    public String greet(Optional<String> name) {   // ✗ callers can still pass null
        return "Hello, " + name.orElse("there");
    }
}
```

<details><summary>Fixed</summary>

```java
public class Greeter {
    public String greet(String name) {
        return "Hello, " + name;
    }

    public String greet() {
        return greet("there");
    }
}
```

</details>

#### `noCheckedExceptions`

`GUARD.api().noCheckedExceptions()` · `no-checked-exceptions`

Checked exceptions don't compose with lambdas and streams, and force every caller to handle what only one place can.
Inherited signatures, such as `AutoCloseable.close()`, and `InterruptedException` are fine.

```java
public class OrderNotFound extends Exception {   // ✗ every caller must catch it
    public OrderNotFound(String orderId) {
        super(orderId);
    }
}

public interface Orders {
    String find(String orderId) throws OrderNotFound;
}
```

<details><summary>Fixed</summary>

```java
public class OrderNotFound extends RuntimeException {
    public OrderNotFound(String orderId) {
        super(orderId);
    }
}

public interface Orders {
    String find(String orderId);
}
```

</details>

#### `noEffectfulStaticMethods`

`GUARD.api().noEffectfulStaticMethods()` · `no-effectful-static-methods`

A static method that does I/O, or reads the clock or the environment, can't be replaced with a fake, so its callers
can't be tested without the effect.

```java
public final class Disk {
    private Disk() {
    }

    public static String read(Path file) throws IOException {
        return Files.readString(file);
    }
}

public class PriceList {
    public long count(Path file) throws IOException {
        return Disk.read(file).lines().count();   // ✗ tests can't replace Disk.read
    }
}
```

<details><summary>Fixed</summary>

`read` is an instance method, and `PriceList` takes a `Disk`.

```java
public class Disk {
    public String read(Path file) throws IOException {
        return Files.readString(file);
    }
}

public class PriceList {
    private final Disk disk;

    public PriceList(Disk disk) {
        this.disk = disk;
    }

    public long count(Path file) throws IOException {
        return disk.read(file).lines().count();
    }
}
```

</details>

#### `typedApis`

`GUARD.api().typedApis()` · `typed-apis`

Public methods that take or return `Object`, a `Map<String, Object>` or a JSON tree leave callers and the compiler
guessing what goes in and comes out. A record names the fields and their types.

```java
public class OrderApi {
    public Map<String, Object> order(String id) {   // ✗ what's in it, and of what type?
        return Map.of("id", id, "totalCents", 1250L);
    }
}
```

<details><summary>Fixed</summary>

```java
public record OrderView(String id, long totalCents) {
}

public class OrderApi {
    public OrderView order(String id) {
        return new OrderView(id, 1250L);
    }
}
```

</details>

#### `noStringlyTypedChoices`

`GUARD.api().noStringlyTypedChoices()` · `no-stringly-typed-choices` · recommended

A `switch` or an `equals` chain over three or more string literals is an enum in disguise. A typo in a string
compiles; a missing enum case doesn't.

```java
public class Shipping {
    public int days(String speed) {
        return switch (speed) {   // ✗ "expres" compiles too
            case "standard" -> 5;
            case "express" -> 2;
            case "overnight" -> 1;
            default -> throw new IllegalArgumentException(speed);
        };
    }
}
```

<details><summary>Fixed</summary>

```java
public enum Speed {
    STANDARD, EXPRESS, OVERNIGHT
}

public class Shipping {
    public int days(Speed speed) {
        return switch (speed) {
            case STANDARD -> 5;
            case EXPRESS -> 2;
            case OVERNIGHT -> 1;
        };
    }
}
```

</details>

#### `accessorsChangeNothing`

`GUARD.api().accessorsChangeNothing()` · `accessors-change-nothing` · recommended

Callers read `get`, `is` and `has` methods as queries and call them freely, so a change hidden in one surprises
them. Lazy initialization is fine.

```java
public class Ticket {
    private int next;

    public int getNumber() {
        return next++;   // ✗ reading the number changes it
    }
}
```

<details><summary>Fixed</summary>

The change gets a name that says so.

```java
public class Ticket {
    private int next;

    public int takeNumber() {
        return next++;
    }
}
```

</details>

#### `minimalVisibility`

`GUARD.api().minimalVisibility()` · `minimal-visibility`

A public type only its own package uses widens the package's API for nothing. Meant for applications, since a
library's public types are its API.

```java
package shop.billing;

public class TaxTable {   // ✗ public, but only billing uses it
    public long tax(long cents) {
        return cents / 5;
    }
}

public class Biller {
    private final TaxTable taxes = new TaxTable();

    public long total(long cents) {
        return cents + taxes.tax(cents);
    }
}
```

<details><summary>Fixed</summary>

```java
package shop.billing;

class TaxTable {
    long tax(long cents) {
        return cents / 5;
    }
}
```

</details>

### Guardrails

#### `noStubs`

`GUARD.guardrails().noStubs()` · `no-stubs` · recommended

AI agents leave placeholders that compile and pass review at a glance: `TODO` and `FIXME` comments, throws saying
"not implemented", and empty methods. A `TODO` that cites an issue, as `TODO(#42)`, is fine.

```java
public class Refunds {
    public long refund(String orderId) {
        // TODO call the payment provider
        throw new UnsupportedOperationException("not implemented yet");   // ✗ compiles, and does nothing
    }
}
```

<details><summary>Fixed</summary>

The work is done.

```java
public interface Payments {
    long refund(String orderId);
}

public class Refunds {
    private final Payments payments;

    public Refunds(Payments payments) {
        this.payments = payments;
    }

    public long refund(String orderId) {
        return payments.refund(orderId);
    }
}
```

</details>

#### `noSuppressions`

`GUARD.guardrails().noSuppressions()` · `no-suppressions` · recommended

Agents silence warnings to get a build green, and the problem stays. Flagged are `@SuppressWarnings`,
`@SuppressFBWarnings`, and `NOPMD`, `NOSONAR`, `noinspection` and `CHECKSTYLE:OFF` comments, in production and test
code. `.allowing("serial")` allows the ones a project accepts.

```java
public class Names {
    @SuppressWarnings("unchecked")   // ✗ hides the warning instead of fixing it
    public List<String> of(Object value) {
        return (List<String>) value;
    }
}
```

<details><summary>Fixed</summary>

```java
public class Names {
    public List<String> of(List<String> value) {
        return List.copyOf(value);
    }
}
```

</details>

#### `noWidenedForTests`

`GUARD.guardrails().noWidenedForTests()` · `no-widened-for-tests` · recommended

A package-private or protected method that only tests call, or one marked `@VisibleForTesting`, was opened up so
tests could reach inside the class, which ties the tests to its internals.

```java
public class Cart {
    private final List<Long> prices = new ArrayList<>();

    public void add(long cents) {
        prices.add(cents);
    }

    public long total() {
        return prices.stream().mapToLong(Long::longValue).sum();
    }

    int size() {   // ✗ only CartTest calls it
        return prices.size();
    }
}
```

```java
class CartTest {
    @Test
    void adds() {
        Cart cart = new Cart();
        cart.add(250);
        assertThat(cart.size()).isEqualTo(1);
    }
}
```

<details><summary>Fixed</summary>

The test checks what the cart offers its callers.

```java
public class Cart {
    private final List<Long> prices = new ArrayList<>();

    public void add(long cents) {
        prices.add(cents);
    }

    public long total() {
        return prices.stream().mapToLong(Long::longValue).sum();
    }
}
```

```java
class CartTest {
    @Test
    void adds() {
        Cart cart = new Cart();
        cart.add(250);
        assertThat(cart.total()).isEqualTo(250);
    }
}
```

</details>

#### `serviceLikeClassesHaveTests`

`GUARD.guardrails().serviceLikeClassesHaveTests()` · `services-have-tests`

Service-like classes hold the behaviour worth testing, and agents skip writing tests. Each needs a test class named
`<Name>Test`, `<Name>Tests` or `<Name>IT`. Not recommended, since projects that test by feature name tests otherwise.

```java
public class ReceiptPrinter {   // ✗ does I/O, and nothing tests it
    public void print(Path file, String receipt) throws IOException {
        Files.writeString(file, receipt);
    }
}
```

<details><summary>Fixed</summary>

```java
import org.junit.jupiter.api.io.TempDir;

class ReceiptPrinterTest {
    @TempDir
    private Path directory;

    @Test
    void printsTheReceipt() throws IOException {
        Path file = directory.resolve("receipt.txt");
        new ReceiptPrinter().print(file, "Total: 4.00");
        assertThat(file).hasContent("Total: 4.00");
    }
}
```

</details>

#### `noUnusedPublicCode`

`GUARD.guardrails().noUnusedPublicCode()` · `no-unused-public-code`

Agents leave code behind that nothing uses, and it still has to be read and maintained. Entry points such as wiring
code, annotated classes and bean accessors count as used. Meant for applications, since in a library unused public
code is the API.

```java
public class Main {
    public static void main(String[] args) {
        System.out.println(new Biller().total(250));
    }
}

public class Biller {
    public long total(long cents) {
        return cents + cents / 5;
    }

    public long totalWithoutTax(long cents) {   // ✗ nothing calls it
        return cents;
    }
}
```

<details><summary>Fixed</summary>

```java
public class Biller {
    public long total(long cents) {
        return cents + cents / 5;
    }
}
```

</details>


#### `noSwallowedErrors`

`GUARD.guardrails().noSwallowedErrors()` · `no-swallowed-errors` · recommended

A handler that catches everything, logs, and returns `null` turns a failure into an answer: a rate service that's
down reads as a currency with no rate, and nothing upstream knows to retry or report it.

```java
public class Rates {
    private static final System.Logger LOG = System.getLogger("rates");

    private final HttpClient http;

    public Rates(HttpClient http) {
        this.http = http;
    }

    public String rate(String currency) {
        try {
            return http.send(request(currency), HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {   // ✗ a failure reads as a missing rate
            LOG.log(System.Logger.Level.WARNING, "no rate for " + currency, e);
            return null;
        }
    }

    private static HttpRequest request(String currency) {
        return HttpRequest.newBuilder(URI.create("https://rates.example.com/" + currency))
                .timeout(Duration.ofSeconds(5))
                .build();
    }
}
```

<details><summary>Fixed</summary>

The failure reaches the caller, which knows whether to retry, fall back or report it.

```java
public class Rates {
    private final HttpClient http;

    public Rates(HttpClient http) {
        this.http = http;
    }

    public String rate(String currency) throws IOException, InterruptedException {
        return http.send(request(currency), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static HttpRequest request(String currency) {
        return HttpRequest.newBuilder(URI.create("https://rates.example.com/" + currency))
                .timeout(Duration.ofSeconds(5))
                .build();
    }
}
```

</details>

#### `writesAreAuthorized`

`GUARD.guardrails().writesAreAuthorized()` · `writes-are-authorized`

A request handler that changes a record by the id it's sent lets anyone change anyone's record. Endpoints that are
public by design, such as sign-up and log-in, and ones a security filter chain authorizes by URL, belong in
`excluding(...)`.

```java
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
    private final Connection db;

    public OrderController(Connection db) {
        this.db = db;
    }

    @PostMapping
    public void cancel(String order) throws SQLException {
        PreparedStatement update = db.prepareStatement("update orders set status = 'cancelled' where id = ?");
        update.setString(1, order);
        update.executeUpdate();   // ✗ cancels whichever order the id names, whoever asks
    }
}
```

<details><summary>Fixed</summary>

The handler takes the current user, and the update only finds that user's orders.

```java
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
    private final Connection db;

    public OrderController(Connection db) {
        this.db = db;
    }

    @PostMapping
    public void cancel(@AuthenticationPrincipal String user, String order) throws SQLException {
        PreparedStatement update = db.prepareStatement(
                "update orders set status = 'cancelled' where id = ? and owner = ?");
        update.setString(1, order);
        update.setString(2, user);
        update.executeUpdate();
    }
}
```

</details>

#### `readsAreAuthorized`

`GUARD.guardrails().readsAreAuthorized()` · `reads-are-authorized`

A request handler that loads a record by the id in the URL shows anyone's record to whoever changes the id. Records
that are public by design, such as published products, belong in `excluding(...)`.

```java
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
    private final OrderRepository orders;

    public OrderController(OrderRepository orders) {
        this.orders = orders;
    }

    @GetMapping
    public Order get(@PathVariable Long id) {
        return orders.findById(id).orElseThrow();   // ✗ shows whichever order the id names, whoever asks
    }
}
```

```java
import org.springframework.data.repository.CrudRepository;

public interface OrderRepository extends CrudRepository<Order, Long> {
}
```

```java
public record Order(Long id, String owner) {
}
```

<details><summary>Fixed</summary>

The handler takes the current user, and the query only finds that user's orders.

```java
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
    private final OrderRepository orders;

    public OrderController(OrderRepository orders) {
        this.orders = orders;
    }

    @GetMapping
    public Order get(@AuthenticationPrincipal String user, @PathVariable Long id) {
        return orders.findByIdAndOwner(id, user).orElseThrow();
    }
}
```

```java
import org.springframework.data.repository.CrudRepository;

public interface OrderRepository extends CrudRepository<Order, Long> {
    Optional<Order> findByIdAndOwner(Long id, String owner);
}
```

</details>

#### `noUnboundedWaits`

`GUARD.guardrails().noUnboundedWaits()` · `no-unbounded-waits` · recommended

A wait with no limit turns a hung report, server or peer into a hung thread, and every request waiting on that
thread hangs too.

```java
public class Reports {
    private final ExecutorService workers;

    public Reports(ExecutorService workers) {
        this.workers = workers;
    }

    public String build(Callable<String> report) throws InterruptedException, ExecutionException {
        return workers.submit(report).get();   // ✗ waits forever if the report hangs
    }
}
```

<details><summary>Fixed</summary>

```java
public class Reports {
    private final ExecutorService workers;

    public Reports(ExecutorService workers) {
        this.workers = workers;
    }

    public String build(Callable<String> report) throws InterruptedException, ExecutionException, TimeoutException {
        return workers.submit(report).get(30, TimeUnit.SECONDS);
    }
}
```

</details>

### Data

#### `noMassAssignment`

`GUARD.data().noMassAssignment()` · `no-mass-assignment` · recommended

A handler that saves the object it binds the request to lets any user who adds `"role": "ADMIN"` to the request make
themselves an admin, though the handler only changes their own account.

```java
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @PutMapping
    public User update(@RequestBody User user) {
        return users.save(user);   // ✗ saves the role the request sent
    }
}
```

```java
import jakarta.persistence.Entity;

@Entity
public class User {
    private Long id;
    private String email;
    private String role;

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
```

```java
import org.springframework.data.repository.CrudRepository;

public interface UserRepository extends CrudRepository<User, Long> {
}
```

<details><summary>Fixed</summary>

The handler binds a form without the role, and copies what it may change onto the user it loaded; an admin's handler,
which `@PreAuthorize` guards, sets roles.

```java
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @PutMapping
    public User update(@AuthenticationPrincipal User current, @RequestBody EmailForm form) {
        current.setEmail(form.email());
        return users.save(current);
    }
}
```

```java
public record EmailForm(String email) {
}
```

</details>

#### `noLostUpdates`

`GUARD.data().noLostUpdates()` · `no-lost-updates` · recommended

Two orders at once both read the same stock, both write their own result, and one sale is lost.

```java
import jakarta.persistence.Entity;

@Entity
public class Product {
    private int stock;

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }
}
```

```java
import org.springframework.transaction.annotation.Transactional;

public class Inventory {
    @Transactional
    public void sell(Product product, int quantity) {
        product.setStock(product.getStock() - quantity);   // ✗ computed from the stock read before
    }
}
```

<details><summary>Fixed</summary>

A version makes the second write fail instead of undoing the first, and the caller retries it.

```java
import jakarta.persistence.Entity;
import jakarta.persistence.Version;

@Entity
public class Product {
    @Version
    private long version;
    private int stock;

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }
}
```

</details>

#### `noFloatingPointMoney`

`GUARD.data().noFloatingPointMoney()` · `no-floating-point-money` · recommended

`0.1 + 0.2` is `0.30000000000000004` in a `double`, so float totals drift by fractions of a cent.

```java
public class Invoice {
    private final double totalPrice;   // ✗ a double can't hold most amounts exactly

    public Invoice(double totalPrice) {
        this.totalPrice = totalPrice;
    }

    public double totalPrice() {
        return totalPrice;
    }
}
```

<details><summary>Fixed</summary>

```java
public class Invoice {
    private final BigDecimal totalPrice;

    public Invoice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    public BigDecimal totalPrice() {
        return totalPrice;
    }
}
```

</details>

#### `noUnboundedQueries`

`GUARD.data().noUnboundedQueries()` · `no-unbounded-queries`

A page that loads every row is fast with a hundred products and times out with a million. A table that stays small,
such as a list of countries, belongs in `excluding(...)`.

```java
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {
    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    public Iterable<Product> list() {
        return products.findAll();   // ✗ loads every product, on every request
    }
}
```

```java
import org.springframework.data.repository.CrudRepository;

public interface ProductRepository extends CrudRepository<Product, Long> {
}
```

```java
public record Product(Long id, String name) {
}
```

<details><summary>Fixed</summary>

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {
    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    public Page<Product> list(Pageable page) {
        return products.findAll(page);
    }
}
```

```java
import org.springframework.data.repository.PagingAndSortingRepository;

public interface ProductRepository extends PagingAndSortingRepository<Product, Long> {
}
```

</details>

### Tests

#### `testsAssertMeaningfully`

`GUARD.tests().testsAssertMeaningfully()` · `meaningful-tests` · recommended

A test that can't fail gives coverage without checking anything, and agents write them to get a suite green. Flagged
are tests without assertions, assertions on constants or a value against itself, and assertions only in a `catch`.

```java
public class Prices {
    public long total(List<Long> cents) {
        return cents.stream().mapToLong(Long::longValue).sum();
    }
}
```

```java
class PricesTest {
    @Test
    void totals() {
        new Prices().total(List.of(150L, 250L));   // ✗ passes whatever total returns
    }
}
```

<details><summary>Fixed</summary>

```java
class PricesTest {
    @Test
    void totals() {
        assertThat(new Prices().total(List.of(150L, 250L))).isEqualTo(400);
    }
}
```

</details>

#### `noDisabledTests`

`GUARD.tests().noDisabledTests()` · `no-disabled-tests` · recommended

Agents disable the tests they can't make pass. Flagged are `@Disabled`, `@Ignore` and `@Test(enabled = false)`;
`.allowingWithReason()` allows `@Disabled("reason")`.

```java
public class Prices {
    public long total(List<Long> cents) {
        return cents.stream().mapToLong(Long::longValue).sum();
    }
}
```

```java
class PricesTest {
    @Disabled   // ✗ hides a failure instead of fixing it
    @Test
    void totalsNothing() {
        assertThat(new Prices().total(List.of())).isZero();
    }
}
```

<details><summary>Fixed</summary>

```java
class PricesTest {
    @Test
    void totalsNothing() {
        assertThat(new Prices().total(List.of())).isZero();
    }
}
```

</details>

#### `testsCheckResults`

`GUARD.tests().testsCheckResults()` · `tests-check-results` · recommended

Verifying that a query was called checks how the code works, not what it returns. Stub queries and assert on the
result; verify only commands, which return nothing.

```java
public interface PriceList {
    long priceOf(String item);
}

public class Checkout {
    private final PriceList prices;

    public Checkout(PriceList prices) {
        this.prices = prices;
    }

    public long total(String item, int quantity) {
        return prices.priceOf(item) * quantity;
    }
}
```

```java
class CheckoutTest {
    private final PriceList prices = mock(PriceList.class);

    @Test
    void totals() {
        new Checkout(prices).total("book", 2);
        verify(prices).priceOf("book");   // ✗ checks a call, not the total
    }
}
```

<details><summary>Fixed</summary>

```java
class CheckoutTest {
    private final PriceList prices = mock(PriceList.class);

    @Test
    void totals() {
        when(prices.priceOf("book")).thenReturn(250L);
        assertThat(new Checkout(prices).total("book", 2)).isEqualTo(500);
    }
}
```

</details>

#### `testsUseTheirSubject`

`GUARD.tests().testsUseTheirSubject()` · `tests-use-their-subject` · recommended

A test named after a class that never uses it, or only mocks it, checks nothing about it.

```java
public interface PriceList {
    long priceOf(String item);
}

public class Checkout {
    private final PriceList prices;

    public Checkout(PriceList prices) {
        this.prices = prices;
    }

    public long total(String item, int quantity) {
        return prices.priceOf(item) * quantity;
    }
}
```

```java
class CheckoutTest {
    private final Checkout checkout = mock(Checkout.class);   // ✗ tests a mock, not Checkout

    @Test
    void totals() {
        when(checkout.total("book", 2)).thenReturn(500L);
        assertThat(checkout.total("book", 2)).isEqualTo(500);
    }
}
```

<details><summary>Fixed</summary>

The test uses a real `Checkout`, and mocks only what it calls.

```java
class CheckoutTest {
    private final PriceList prices = mock(PriceList.class);

    @Test
    void totals() {
        when(prices.priceOf("book")).thenReturn(250L);
        assertThat(new Checkout(prices).total("book", 2)).isEqualTo(500);
    }
}
```

</details>

#### `testsDontMockValues`

`GUARD.tests().testsDontMockValues()` · `tests-dont-mock-values` · recommended

A value is cheap and safe to create, so a test uses a real one. Flagged are mocks of records, enums and other values,
and of interfaces whose implementations are all values.

```java
public record Item(String name, long cents) {
}

public class Basket {
    public long total(List<Item> items) {
        return items.stream().mapToLong(Item::cents).sum();
    }
}
```

```java
class BasketTest {
    @Test
    void totals() {
        Item book = mock(Item.class);   // ✗ a record is cheap to create for real
        when(book.cents()).thenReturn(250L);
        assertThat(new Basket().total(List.of(book))).isEqualTo(250);
    }
}
```

<details><summary>Fixed</summary>

```java
class BasketTest {
    @Test
    void totals() {
        assertThat(new Basket().total(List.of(new Item("book", 250)))).isEqualTo(250);
    }
}
```

</details>

#### `testsHaveNoLogic`

`GUARD.tests().testsHaveNoLogic()` · `tests-have-no-logic` · recommended

A test takes one path, so a failure points at one case. Flagged are `if` and `switch` in tests, and loops around
assertions; a loop that only builds test data is fine.

```java
public class Discount {
    public long apply(long cents) {
        return cents >= 10_000 ? cents * 9 / 10 : cents;
    }
}
```

```java
class DiscountTest {
    @Test
    void discountsLargeOrders() {
        for (long cents : new long[] {10_000, 20_000}) {   // ✗ which case failed?
            assertThat(new Discount().apply(cents)).isEqualTo(cents * 9 / 10);
        }
    }
}
```

<details><summary>Fixed</summary>

```java
class DiscountTest {
    @ParameterizedTest
    @ValueSource(longs = {10_000, 20_000})
    void discountsLargeOrders(long cents) {
        assertThat(new Discount().apply(cents)).isEqualTo(cents * 9 / 10);
    }
}
```

</details>

#### `testsUseAFixedClock`

`GUARD.tests().testsUseAFixedClock()` · `tests-fixed-clock` · recommended

A test that reads the system clock can pass today and fail at midnight, at the end of a month, or in another time
zone.

```java
public record Invoice(LocalDate due) {
    public boolean overdueOn(LocalDate day) {
        return day.isAfter(due);
    }
}
```

```java
class InvoiceTest {
    @Test
    void isOverdueAfterItsDueDate() {
        LocalDate today = LocalDate.now();   // ✗ a different date every day
        assertThat(new Invoice(today.minusDays(1)).overdueOn(today)).isTrue();
    }
}
```

<details><summary>Fixed</summary>

```java
class InvoiceTest {
    @Test
    void isOverdueAfterItsDueDate() {
        LocalDate today = LocalDate.of(2026, 1, 31);
        assertThat(new Invoice(today.minusDays(1)).overdueOn(today)).isTrue();
    }
}
```

</details>

#### `testsUseSeededRandomness`

`GUARD.tests().testsUseSeededRandomness()` · `tests-seeded-randomness` · recommended

A test on random data can fail once and never again, and the failing case is gone.

```java
public class Discount {
    public long apply(long cents) {
        return cents >= 10_000 ? cents * 9 / 10 : cents;
    }
}
```

```java
class DiscountTest {
    @Test
    void neverRaisesThePrice() {
        long cents = new Random().nextInt(100_000);   // ✗ a failure can't be reproduced
        assertThat(new Discount().apply(cents)).isLessThanOrEqualTo(cents);
    }
}
```

<details><summary>Fixed</summary>

```java
class DiscountTest {
    @Test
    void neverRaisesThePrice() {
        long cents = new Random(42).nextInt(100_000);
        assertThat(new Discount().apply(cents)).isLessThanOrEqualTo(cents);
    }
}
```

</details>

#### `testsDontReadTheEnvironment`

`GUARD.tests().testsDontReadTheEnvironment()` · `tests-no-environment` · recommended

A test that reads environment variables or system properties passes on one machine and fails on another, or in CI.

```java
public class Shipping {
    private final String region;

    public Shipping(String region) {
        this.region = region;
    }

    public long cost(long cents) {
        return "EU".equals(region) ? cents / 10 : cents / 5;
    }
}
```

```java
class ShippingTest {
    @Test
    void chargesAFifthOutsideTheEu() {
        Shipping shipping = new Shipping(System.getenv("REGION"));   // ✗ depends on the machine
        assertThat(shipping.cost(1000)).isEqualTo(200);
    }
}
```

<details><summary>Fixed</summary>

```java
class ShippingTest {
    @Test
    void chargesAFifthOutsideTheEu() {
        assertThat(new Shipping("US").cost(1000)).isEqualTo(200);
    }
}
```

</details>

#### `testsDontSleep`

`GUARD.tests().testsDontSleep()` · `tests-no-sleep` · recommended

A sleep long enough on a fast machine is too short on a slow one, and slows every run.

```java
public class Mailer {
    private final Executor executor;
    private final List<String> sent = new CopyOnWriteArrayList<>();

    public Mailer(Executor executor) {
        this.executor = executor;
    }

    public void sendLater(String to) {
        executor.execute(() -> sent.add(to));
    }

    public List<String> sent() {
        return List.copyOf(sent);
    }
}
```

```java
class MailerTest {
    @Test
    void sendsInTheBackground() throws InterruptedException {
        Mailer mailer = new Mailer(Executors.newSingleThreadExecutor());
        mailer.sendLater("ada@example.com");
        Thread.sleep(500);   // ✗ too short on a slow machine, too long on a fast one
        assertThat(mailer.sent()).containsExactly("ada@example.com");
    }
}
```

<details><summary>Fixed</summary>

The test runs the work at once. Where it can't, Awaitility's `await().until(...)` waits for the result.

```java
class MailerTest {
    @Test
    void sendsInTheBackground() {
        Mailer mailer = new Mailer(Runnable::run);
        mailer.sendLater("ada@example.com");
        assertThat(mailer.sent()).containsExactly("ada@example.com");
    }
}
```

</details>

## Configuration

Every rule takes `that().resideInAPackage(...)`, `excluding(...)`, `ignoring(annotatedWith(...))` (from
`AnnotationName`) and `frozen(...)`. `ignoring` skips annotated classes and the code in them, and annotated fields,
methods and constructors. Package patterns use ArchUnit's package syntax (`..domain..`). `check()` throws an
`AssertionError` listing every violation; `evaluate()` returns them.

`new DesignGuard("com.shop")` reads sources from `src/main/java` and `src/test/java`, and classes from the classpath.
To change anything, configure it with a builder:

```java
private static final DesignGuard GUARD = DesignGuard.forPackages("com.shop")
        .wiringIn("..config..")
        .treatingAsImpure("com.stripe..")
        .build();
```

- `withSourceRoots`, `withTestSourceRoots`, `withClassesFrom`: where the code is.
- `wiringIn("..config..")`: wiring code may create and hold concrete services. Classes with a `main` method, and
  `@Configuration`, `@SpringBootApplication` or `@Module` classes, always are wiring code.
- `treatingAsImpure("com.stripe..")`: using these packages counts as I/O, like `java.net`.
- `forJava(17)`: the Java version the code targets, if reading it from the class files isn't right (see below).
- `freezingIn(...)`: where frozen rules record their violations, instead of `src/test/designguard/frozen`.

## Warnings

`asWarning()` makes a rule report without failing:

```java
GUARD.api().typedApis().asWarning().check();                                     // logs, passes
GUARD.recommended().warningOn("no-suppressions", "tests-have-no-logic").check(); // those two warn, the rest fail
```

- Warnings are logged at `WARNING` through `System.Logger`, the JDK's logging facade. They reach the project's
  logging backend if one is bridged to it, and standard error if not.
- `evaluate()` returns them with `Severity.WARNING`, and the report marks each line `warning`.
- A frozen warning rule logs its new violations instead of failing.
- `Checkable` is what rules, rule sets and frozen rules have in common: `evaluate()` and `check()`.

## Reports for tools

`SarifReport` and `JsonReport` render violations for machines. SARIF 2.1.0 is what GitHub code scanning shows as
annotations on pull requests; the JSON lists each violation's rule, severity, file, line, message, fix and identity.
To write a report and fail with one run of the rules, check what `evaluate()` found:

```java
import io.github.yoavgeva.designguard.Checkable;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.report.SarifReport;

@Test
void recommended() throws IOException {
    List<Violation> found = GUARD.recommended().evaluate();
    Files.createDirectories(Path.of("target"));
    Files.writeString(Path.of("target/designguard.sarif"), new SarifReport().render(found));
    Checkable.check(found);
}
```

```yaml
- uses: github/codeql-action/upload-sarif@v4
  if: always()
  with:
    sarif_file: target/designguard.sarif
```

Paths relative to the repository root stay relative, as code scanning expects. Each result's fingerprint is its file,
rule and identity, so an alert keeps its history as the code around it moves.

## Java versions

designguard runs on Java 17 and later, and checks code compiled for any Java version. It reads the version the
code targets from its class files, so its advice fits that version:

- Rules built on newer features fail with an explanation when the code targets an older Java:
  `dataClassesAreRecords()` needs Java 16, and `closedHierarchiesAreSealed()` and `typeDispatch(SEALED_ONLY)` need
  Java 17.
- Fixes only suggest what the code's Java has: an exhaustive `switch` over a sealed type from Java 21, over an enum
  from Java 14, and virtual threads from Java 21.
- From Java 25, instance and parameterless `main` methods mark wiring code too.

If designguard finds no compiled classes in the packages, rules that read classes fail instead of passing with
nothing checked. That also happens when the classes target a newer Java than its ArchUnit version reads: ArchUnit
1.5.1 reads up to Java 27.

Rules also see classes that ArchUnit doesn't import, which designguard reads from the class files with ASM:
- classes a `switch` only matches on, as in `case Circle c -> "circle"` or `case Suit.CLUBS`;
- classes whose constants the code reads, as in `Limits.PAGE_SIZE`. The compiler copies the value into the code and
  keeps only the class's name, so these dependencies are reported at line 0;
- the outer class of a nested class the code uses, such as `Order` in `Order.Status`, so `minimalVisibility` and
  `noUnusedPublicCode` count `Order` as used.

## Speed and repeatability

A guard reads the classes and sources once and shares them between its rules, so keep it in a `static final` field.
All rules together take about 4 s on Guava (2,000 classes, 630 sources) and about 14 s on Hibernate ORM's core (6,900
classes, 5,200 sources), using up to 4 GB of memory.

Every rule reports the same violations, in the same order and with the same wording, each time it reads the same
code, so frozen records and CI results don't flap between runs.

## Use it alongside

designguard doesn't repeat what these do well:

- [ArchUnit](https://www.archunit.org/): custom rules, cycles, naming
- [PMD](https://pmd.github.io/): complexity thresholds, God Class, copy-paste detection
- [Maven Enforcer](https://maven.apache.org/enforcer/): a dependency allowlist

## Build

Builds with Java 25 and Maven 3.9, pinned in `mise.toml`. The library compiles for Java 17 (`--release 17`), and CI
runs the tests on Java 17, 21, 25 and 27. Each JVM compiles the test fixtures for every LTS it can target and for
its own version, so rules that read classes are tested on each version's class files.

```shell
mise install
mise exec -- mvn verify
```

`verify` runs in CI on every push to `main` and every pull request, and fails on any of:

- **Maven Enforcer:** Java/Maven versions, unpinned plugins, dependency conflicts
- **Compiler:** any `-Xlint:all` warning
- **ArchUnit** (`ArchitectureTest`): the API, internal, model and report packages' layering, no internal types in the
  API, no cycles, immutable model, no stdout/stderr, no generic exceptions, no field injection, no deprecated APIs
- **designguard on itself** (`DesignGuardSelfTest`): its recommended rules and 44 others, including the `SERVICES`
  interface policy, `@NullMarked` packages and sealed hierarchies
- **The README** (`ReadmeExamplesTest`): every rule's example is compiled, flagged by its rule, and passes once fixed
- **PMD** (`config/pmd/ruleset.xml`): every Java category, every priority, with exclusions documented in the file
- **CPD:** duplicated code of 100 tokens or more

Releasing is described in [docs/releasing.md](docs/releasing.md).

## License

[Apache License 2.0](LICENSE)
