# designguard

[![CI](https://github.com/yoavgeva/designguard/actions/workflows/ci.yml/badge.svg)](https://github.com/yoavgeva/designguard/actions/workflows/ci.yml)

Deterministic design rules for AI-written Java code, run as plain JUnit tests.

AI agents write a lot of code. Tests tell you whether it works; designguard tells the agent, in one actionable line
per problem, whether it kept the design healthy: where, which rule, and what to change.

```
src/main/java/shop/Checkout.java:7: error [inject-services] Checkout creates service-like StripeClient with new -> fix: take StripeClient as a constructor parameter, and create it only in wiring code
```

> Status: pre-alpha.

## Use it

Start with the recommended rules. They need no configuration:

```java
class DesignTest {

    private static final DesignGuard GUARD = new DesignGuard("com.shop");

    @Test
    void recommended() {
        GUARD.recommended().check();
    }
}
```

They cover three kinds of problem:

- AI shortcuts: stubs, suppressions, and code widened for tests.
- Tests that can't fail or don't repeat: no assertions, mocked values, logic in tests, the system clock, unseeded
  randomness, the environment, sleeping.
- Design faults: class cycles, queries in loops, work in constructors, accessors that change state, flag arguments,
  choices between strings, misused `Optional`, `int` constants for cases, mutable records, fat interfaces and raw
  threads.

`recommended().rules()` lists their ids, and `without("no-suppressions")` leaves one out. On existing code, adopt
them with `GUARD.recommended().frozen("recommended").check()` (see below). A new designguard version may add rules.

Architecture styles and opinionated rules are a choice, so pick them as tests of their own:

```java
class DesignTest {

    private static final DesignGuard GUARD = new DesignGuard("com.shop");

    @Test
    void hexagonal() {
        GUARD.architecture().hexagonal()
                .adapter("web", "..adapter.web..").adapter("persistence", "..adapter.persistence..")
                .check();
    }

    @Test
    void dependOnPorts() {
        GUARD.dependencies().interfacePolicy(InterfacePolicy.PORTS).excluding("..legacy..").check();
    }

    @Test
    void sealedDispatch() {
        GUARD.types().typeDispatch(TypeDispatchPolicy.SEALED_ONLY).maxPlacesPerType(3).check();
    }

    @Test
    void noAiShortcuts() {
        GUARD.guardrails().noStubs().check();
        GUARD.guardrails().noSuppressions().allowing("serial").check();
        GUARD.tests().testsAssertMeaningfully().check();
    }
}
```

Every rule takes `that().resideInAPackage(...)`, `excluding(...)`, `ignoring(annotatedWith(...))` (from
`AnnotationName`) and `frozen(...)` (see below). `ignoring` skips annotated classes and the code in them, and annotated
fields, methods and constructors. Package patterns use ArchUnit's package syntax (`..domain..`). `check()` throws an
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
- `freezingIn(...)`: where frozen rules record their violations, instead of `src/test/designguard/frozen` (see below).

## Adopting a rule on existing code

`frozen(name)` makes a rule fail only on new violations, so you can adopt it on code that already breaks it:

```java
@Test
void layers() {
    GUARD.architecture().layered()
            .layer("Web", "..web..").layer("Persistence", "..persistence..")
            .frozen("layers")
            .check();
}
```

- The first run records the current violations in `src/test/designguard/frozen/layers.txt` and fails once, so the
  file gets committed. Until the file exists, the rule keeps failing, so CI catches a record nobody committed.
- Later runs fail only on violations the file doesn't list. A violation is matched by its file, rule and what it's
  about, not its line or the counts in its message, so editing elsewhere keeps it frozen. Each listed violation
  accepts one occurrence.
- Fixed violations drop out of the file, so it only shrinks and a fixed problem can't come back. Commit the change.
- Renaming a class changes its violations' messages, so they show up as new. To accept the current violations again,
  delete the file and run the rule.
- Give each frozen rule its own name: the name is the file's. A rule run against another rule's file fails instead
  of overwriting it.
- A rule set such as `recommended()` freezes all its rules in one file.

## Warnings

`asWarning()` makes a rule report without failing:

```java
GUARD.api().typedApis().asWarning().check();                          // logs, passes
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
@Test
void recommended() throws IOException {
    List<Violation> found = GUARD.recommended().evaluate();
    Files.createDirectories(Path.of("target"));
    Files.writeString(Path.of("target/designguard.sarif"), new SarifReport().render(found));
    Checkable.check(found);
}
```

```yaml
- uses: github/codeql-action/upload-sarif@v3
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

## Rules

A class is **effectful** if its code touches the outside world: I/O, the clock, randomness, the environment, threads,
or an impure package. For threads and executors, starting, configuring or running work on one counts; holding one,
or reading the current thread, doesn't. That holds directly, through a field holding such a resource, or by calling project code that
is effectful. It is **service-like** if it is effectful, holds a service, or is an interface with a service-like
implementation. Everything else is a **value**.

| Category | Rule | Fails on |
|---|---|---|
| `architecture()` | `layered().layer(...)` (`.strict()`) | a layer using a layer above it (or skipping one) |
| | `mvc()` | views or services using anything but models; models using other layers |
| | `hexagonal().adapter(...)` | the domain using anything else; adapters using each other |
| | `onion().adapter(...)` | rings depending outwards |
| | `featureSlices()` | a feature using another's internals, or features in a cycle |
| | `adaptersStayThin("..web..")` | adapter methods, such as controllers, making more than two decisions instead of delegating |
| | `classesHaveNoCycles()` | classes that depend on each other, directly or around a loop |
| | `frameworksStayOutOf("..domain..")` | Spring, Jakarta EE, Hibernate, Jackson, Guice, Micronaut, Quarkus... types or annotations in the domain |
| | `infrastructureExceptionsStayOutOf("..domain..")` | `SQLException`, `IOException`, JPA or Spring data exceptions in the domain |
| | `entitiesStayOutOf("..web..")` | persistence entities used past the persistence layer |
| `dependencies()` | `interfacePolicy(PORTS / SERVICES / ALL)` | concrete dependencies where an interface is required |
| | `injectServices()` | effectful classes created with `new`, singletons reached statically, or services looked up (`getBean`, `ServiceLoader`, JNDI, Guice, CDI), outside wiring code; a class whose effects happen only in implementations it is given doesn't count |
| | `noQueriesInLoops()` | a collaborator's query (I/O that returns a value) called once per item, in a loop or a stream: N+1 |
| | `constructorsDoNoWork()` | constructors and static initializers that call I/O, the clock, an injected collaborator or other effectful code |
| | `clockIsInjected()` | `LocalDate.now()`, `System.currentTimeMillis()`... instead of an injected `Clock` |
| | `randomnessIsInjected()` | `new Random()`, `UUID.randomUUID()`... |
| | `configurationIsInjected()` | `System.getenv()`, `System.getProperty()`... |
| | `noRawThreads()` | `new Thread(...)` instead of an executor |
| | `stableDependencies()` | a slice depending on a less stable slice |
| `types()` | `typeDispatch(SEALED_ONLY / ALLOW)` | switches or `instanceof` chains on open hierarchies, `default` branches hiding new cases, a type branched on in too many unchecked places |
| | `recordsAreImmutable()` | record components callers can still change |
| | `dataClassesAreRecords()` | classes that only carry data |
| | `valueTypesAreImmutable()` | values with non-final fields |
| | `typedIdentifiers("..domain..")` | identifiers like `String customerId` or `long orderId` instead of their own types |
| | `interfacesAreFocused()` | interfaces with 4+ methods whose 2+ clients each use at most half of them |
| | `enumsOverConstants()` | int or String constants enumerating cases, like `STATUS_OPEN = 0`, `STATUS_PAID = 1` |
| | `servicesAreStateless()` | service-like classes with non-final fields, or fields holding mutable collections, atomics or builders |
| | `closedHierarchiesAreSealed()` | interfaces implemented only in the project but not sealed |
| | `inheritance(FINAL_BY_DEFAULT / ABSTRACT_ONLY / ANY).maxDepth(n)` | non-final leaves, extending concrete classes, deep hierarchies |
| `api()` | `noFlagArguments()` | boolean parameters that pick between behaviours |
| | `packagesAreNullMarked()` | packages without JSpecify's `@NullMarked` |
| | `optionalUsage()` | `Optional` fields and parameters, `return null` from an `Optional` method |
| | `noCheckedExceptions()` | checked exception classes and declarations |
| | `accessorsChangeNothing()` | `get`/`is`/`has` methods that assign a field, apart from lazy initialization |
| | `noStringlyTypedChoices()` | `switch`es or `equals` chains over three or more string literals that should be an enum |
| | `typedApis()` | public methods taking or returning `Object`, `Map<String, Object>` or JSON trees instead of records |
| | `noEffectfulStaticMethods()` | static methods doing I/O, reading the clock or the environment, that other classes call and so can't replace in tests |
| | `minimalVisibility()` | public types only their own package uses (applications) |
| `guardrails()` | `noStubs()` | `TODO`/`FIXME`, `UnsupportedOperationException` placeholders, empty methods |
| | `noSuppressions().allowing(...)` | `@SuppressWarnings`, `NOPMD`, `NOSONAR`, `noinspection`, `CHECKSTYLE:OFF` |
| | `noWidenedForTests()` | package-private or protected methods and constructors only tests call |
| | `serviceLikeClassesHaveTests()` | service-like classes with no `<Name>Test` |
| | `noUnusedPublicCode()` | public types and methods nothing uses (applications) |
| `tests()` | `testsAssertMeaningfully()` | tests asserting nothing, on constants, or a value against itself |
| | `noDisabledTests()` (`.allowingWithReason()`) | `@Disabled`, `@Ignore`, `@Test(enabled = false)` |
| | `testsHaveNoLogic()` | `if` or `switch` in test methods, or loops around assertions |
| | `testsUseAFixedClock()` | tests reading the system clock: `LocalDate.now()`, `System.currentTimeMillis()`... |
| | `testsUseSeededRandomness()` | tests using unseeded randomness: `new Random()`, `UUID.randomUUID()`... |
| | `testsDontReadTheEnvironment()` | tests reading environment variables or system properties |
| | `testsDontSleep()` | tests calling `Thread.sleep` or `TimeUnit.X.sleep` |
| | `testsCheckResults()` | `verify(mock).query()` on methods that return a value, instead of stubbing them and asserting on the result |
| | `testsUseTheirSubject()` | `OrderServiceTest` never using `OrderService`, or only mocking it |
| | `testsDontMockValues()` | mocks of records, enums, other values, and interfaces whose implementations are all values |

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
- **ArchUnit** (`ArchitectureTest`): model/view/controller layering with a service layer, no cycles, immutable model,
  no stdout/stderr, no generic exceptions, no field injection, no deprecated APIs
- **designguard on itself** (`DesignGuardSelfTest`): 22 of its own rules, including the `SERVICES` interface policy,
  `@NullMarked` packages and sealed hierarchies
- **PMD** (`config/pmd/ruleset.xml`): every Java category, every priority, with exclusions documented in the file
- **CPD:** duplicated code of 100 tokens or more

## License

[Apache License 2.0](LICENSE)
