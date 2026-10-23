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
        GUARD.guardrails().testsAssertMeaningfully().check();
    }
}
```

Every rule takes `that().resideInAPackage(...)`, `excluding(...)` and `ignoring(annotatedWith(...))`, with ArchUnit's
package syntax (`..domain..`). `check()` throws an `AssertionError` listing every violation; `evaluate()` returns them.

`new DesignGuard("com.shop")` reads sources from `src/main/java` and `src/test/java`, and classes from the classpath.
To change anything, configure it with a builder:

```java
private static final DesignGuard GUARD = DesignGuard.forPackages("com.shop")
        .wiringIn("..config..")
        .treatingAsImpure("com.stripe..")
        .build();
```

- `withSourceRoots`, `withTestSourceRoots`, `withClassesFrom`: where the code is.
- `wiringIn("..config..")`: wiring code may create and hold concrete services. Classes with `main`, and
  `@Configuration`, `@SpringBootApplication` or `@Module` classes, always are wiring code.
- `treatingAsImpure("com.stripe..")`: using these packages counts as I/O, like `java.net`.
- `forJava(17)`: the Java version the code targets, if reading it from the class files isn't right (see below).

## Java versions

designguard runs on Java 17 and later, and checks code compiled for any Java version. It reads the version the
code targets from its class files, so its advice fits that version:

- Rules built on newer features fail with an explanation when the code targets an older Java:
  `dataClassesAreRecords()` needs Java 16, and `closedHierarchiesAreSealed()` and `typeDispatch(SEALED_ONLY)` need
  Java 17.
- Fixes only suggest what the code's Java has: an exhaustive `switch` over a sealed type from Java 21, over an enum
  from Java 14, and virtual threads from Java 21.

## Rules

A class is **effectful** if its code touches the outside world: I/O, the clock, randomness, the environment, threads,
or an impure package. That holds directly, through a field holding such a resource, or by calling project code that
is effectful. It is **service-like** if it is effectful, holds a service, or is an interface with a service-like
implementation. Everything else is a **value**.

| Category | Rule | Fails on |
|---|---|---|
| `architecture()` | `layered().layer(...)` (`.strict()`) | a layer using a layer above it (or skipping one) |
| | `mvc()` | views or services using anything but models; models using other layers |
| | `hexagonal().adapter(...)` | the domain using anything else; adapters using each other |
| | `onion().adapter(...)` | rings depending outwards |
| | `featureSlices()` | a feature using another's internals, or features in a cycle |
| `dependencies()` | `interfacePolicy(PORTS / SERVICES / ALL)` | concrete dependencies where an interface is required |
| | `injectServices()` | effectful classes created with `new`, or singletons reached statically, outside wiring code |
| | `clockIsInjected()` | `LocalDate.now()`, `System.currentTimeMillis()`... instead of an injected `Clock` |
| | `randomnessIsInjected()` | `new Random()`, `UUID.randomUUID()`... |
| | `configurationIsInjected()` | `System.getenv()`, `System.getProperty()`... |
| | `noRawThreads()` | `new Thread(...)` instead of an executor |
| | `stableDependencies()` | a slice depending on a less stable slice |
| | `infrastructureExceptionsStayOutOf("..domain..")` | `SQLException`, `IOException`, JPA or Spring data exceptions in the domain |
| | `entitiesStayOutOf("..web..")` | persistence entities used past the persistence layer |
| `types()` | `typeDispatch(SEALED_ONLY / ALLOW)` | switches or `instanceof` chains on open hierarchies, `default` branches hiding new cases, a type branched on in too many unchecked places |
| | `recordsAreImmutable()` | record components callers can still change |
| | `dataClassesAreRecords()` | classes that only carry data |
| | `valueTypesAreImmutable()` | values with non-final fields |
| | `closedHierarchiesAreSealed()` | interfaces implemented only in the project but not sealed |
| | `inheritance(FINAL_BY_DEFAULT / ABSTRACT_ONLY / ANY).maxDepth(n)` | non-final leaves, extending concrete classes, deep hierarchies |
| `api()` | `noFlagArguments()` | boolean parameters that pick between behaviours |
| | `packagesAreNullMarked()` | packages without JSpecify's `@NullMarked` |
| | `optionalUsage()` | `Optional` fields and parameters, `return null` from an `Optional` method |
| | `noCheckedExceptions()` | checked exception classes and declarations |
| | `minimalVisibility()` | public types only their own package uses (applications) |
| `guardrails()` | `noStubs()` | `TODO`/`FIXME`, `UnsupportedOperationException` placeholders, empty methods |
| | `noSuppressions().allowing(...)` | `@SuppressWarnings`, `NOPMD`, `NOSONAR`, `noinspection`, `CHECKSTYLE:OFF` |
| | `testsAssertMeaningfully()` | tests asserting nothing, on constants, or a value against itself |
| | `noDisabledTests()` (`.allowingWithReason()`) | `@Disabled`, `@Ignore`, `@Test(enabled = false)` |
| | `serviceLikeClassesHaveTests()` | service-like classes with no `<Name>Test` |
| | `noUnusedPublicCode()` | public types and methods nothing uses (applications) |

## Use it alongside

designguard doesn't repeat what these do well:

- [ArchUnit](https://www.archunit.org/): custom rules, cycles, naming, `FreezingArchRule`
- [PMD](https://pmd.github.io/): complexity thresholds, God Class, copy-paste detection
- [Maven Enforcer](https://maven.apache.org/enforcer/): a dependency allowlist

## Build

Builds with Java 25 and Maven 3.9, pinned in `mise.toml`. The library compiles for Java 17 (`--release 17`), and CI
runs the tests on Java 17, 21 and 25.

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
