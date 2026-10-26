package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.model.AnnotationName;
import io.github.yoavgeva.designguard.model.InterfacePolicy;
import io.github.yoavgeva.designguard.model.TypeDispatchPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code ignoring(annotatedWith(...))} skips members, not only classes: for each rule that reports a field, method,
 * constructor or call, annotating just that member clears the violation. Runs once for each Java version's class
 * files: see {@link CompiledProject#releases()}.
 */
@ParameterizedClass(name = "class files for Java {0}")
@MethodSource("io.github.yoavgeva.designguard.CompiledProject#releases")
class MemberIgnoringContractTest {

    private static final String PACKAGE = "package shop;\n\n";
    private static final String RUNTIME = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n";
    private static final AnnotationName LEGACY = new AnnotationName("Legacy", "shop.Legacy");
    private static final Pattern TYPE_NAME = Pattern.compile("(?:class|interface|record|enum) (\\w+)");
    private static final String REPOSITORY = "package org.springframework.data.repository;\n\n"
            + "public interface Repository<T, ID> {\n}\n";
    private static final String CRUD_REPOSITORY = "package org.springframework.data.repository;\n\n"
            + "public interface CrudRepository<T, ID> extends Repository<T, ID> {\n    Iterable<T> findAll();\n\n"
            + "    java.util.Optional<T> findById(ID id);\n\n    <S extends T> S save(S entity);\n}\n";
    private static final String STRIPE_CLIENT = """
            package shop;

            public class StripeClient {
                private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

                public String charge() {
                    return http.toString() + System.getenv("STRIPE_KEY");
                }
            }
            """;

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    static Stream<Arguments> rules() {
        return Stream.of(
                member("injectServices", g -> g.dependencies().injectServices(), List.of(STRIPE_CLIENT,
                        "package shop;\n\npublic class Checkout {\n    @Legacy\n    public String pay() {\n"
                                + "        return new StripeClient().charge();\n    }\n}\n"), List.of()),
                member("clockIsInjected", g -> g.dependencies().clockIsInjected(), List.of(PACKAGE
                        + "public class Stamps {\n    @Legacy\n    public long now() {\n"
                        + "        return System.currentTimeMillis();\n    }\n}\n"), List.of()),
                member("randomnessIsInjected", g -> g.dependencies().randomnessIsInjected(), List.of(PACKAGE
                        + "public class Ids {\n    @Legacy\n    public String next() {\n"
                        + "        return java.util.UUID.randomUUID().toString();\n    }\n}\n"), List.of()),
                member("configurationIsInjected", g -> g.dependencies().configurationIsInjected(), List.of(PACKAGE
                        + "public class Settings {\n    @Legacy\n    public String region() {\n"
                        + "        return System.getenv(\"REGION\");\n    }\n}\n"), List.of()),
                member("noIoInTransactions", g -> g.dependencies().noIoInTransactions(), List.of(
                        "package org.springframework.transaction.annotation;\n\n" + RUNTIME
                                + "public @interface Transactional {\n}\n",
                        PACKAGE + "public class Refunds {\n    @Legacy\n"
                                + "    @org.springframework.transaction.annotation.Transactional\n"
                                + "    public java.io.InputStream refund(java.net.URL bank) throws java.io.IOException {\n"
                                + "        return bank.openStream();\n    }\n}\n"), List.of()),
                member("noSwallowedErrors", g -> g.guardrails().noSwallowedErrors(), List.of(PACKAGE
                        + "public class Rates {\n    @Legacy\n"
                        + "    public String rate(java.util.function.Supplier<String> source) {\n        try {\n"
                        + "            return source.get();\n        } catch (Exception e) {\n            return null;\n"
                        + "        }\n    }\n}\n"), List.of()),
                member("writesAreAuthorized", g -> g.guardrails().writesAreAuthorized(), List.of(
                        "package org.springframework.web.bind.annotation;\n\n" + RUNTIME
                                + "public @interface RestController {\n}\n",
                        "package org.springframework.web.bind.annotation;\n\n" + RUNTIME
                                + "public @interface PostMapping {\n}\n",
                        "package org.springframework.data.repository;\n\npublic interface Repository<T, ID> {\n}\n",
                        PACKAGE + "public interface Articles extends org.springframework.data.repository.Repository<String, "
                                + "Long> {\n    String save(String article);\n}\n",
                        PACKAGE + "@org.springframework.web.bind.annotation.RestController\npublic class ArticleController {\n"
                                + "    private final Articles articles = null;\n\n    @Legacy\n"
                                + "    @org.springframework.web.bind.annotation.PostMapping\n"
                                + "    public String publish(String article) {\n        return articles.save(article);\n"
                                + "    }\n}\n"), List.of()),
                member("readsAreAuthorized", g -> g.guardrails().readsAreAuthorized(), List.of(
                        web("RestController"), web("GetMapping"),
                        web("PathVariable"), REPOSITORY, CRUD_REPOSITORY,
                        PACKAGE + "public interface Orders extends org.springframework.data.repository.CrudRepository<String, "
                                + "Long> {\n}\n",
                        PACKAGE + "@org.springframework.web.bind.annotation.RestController\npublic class OrderController {\n"
                                + "    private final Orders orders = null;\n\n    @Legacy\n"
                                + "    @org.springframework.web.bind.annotation.GetMapping\n"
                                + "    public String one(@org.springframework.web.bind.annotation.PathVariable Long id) {\n"
                                + "        return orders.findById(id).orElseThrow();\n    }\n}\n"), List.of()),
                member("noUnboundedQueries", g -> g.data().noUnboundedQueries(), List.of(
                        web("RestController"), web("GetMapping"),
                        REPOSITORY, CRUD_REPOSITORY,
                        PACKAGE + "public interface Orders extends org.springframework.data.repository.CrudRepository<String, "
                                + "Long> {\n}\n",
                        PACKAGE + "@org.springframework.web.bind.annotation.RestController\npublic class OrderController {\n"
                                + "    private final Orders orders = null;\n\n    @Legacy\n"
                                + "    @org.springframework.web.bind.annotation.GetMapping\n"
                                + "    public Iterable<String> all() {\n        return orders.findAll();\n    }\n}\n"), List.of()),
                member("noMassAssignment", g -> g.data().noMassAssignment(), List.of(
                        web("RestController"), web("PutMapping"),
                        web("RequestBody"),
                        REPOSITORY, CRUD_REPOSITORY,
                        PACKAGE + "public record AccountForm(String email, String role) {\n}\n",
                        PACKAGE + "public interface Forms extends org.springframework.data.repository.CrudRepository<"
                                + "AccountForm, Long> {\n}\n",
                        PACKAGE + "@org.springframework.web.bind.annotation.RestController\npublic class AccountController {\n"
                                + "    private final Forms forms = null;\n\n"
                                + "    @Legacy\n    @org.springframework.web.bind.annotation.PutMapping\n"
                                + "    public AccountForm update(@org.springframework.web.bind.annotation.RequestBody AccountForm form) {\n"
                                + "        return forms.save(form);\n    }\n}\n"), List.of()),
                member("noLostUpdates", g -> g.data().noLostUpdates(), List.of(
                        "package jakarta.persistence;\n\n" + RUNTIME + "public @interface Entity {\n}\n",
                        "package org.springframework.transaction.annotation;\n\n" + RUNTIME
                                + "public @interface Transactional {\n}\n",
                        PACKAGE + "@jakarta.persistence.Entity\npublic class Stock {\n    private int count;\n\n"
                                + "    public int getCount() {\n        return count;\n    }\n\n"
                                + "    public void setCount(int count) {\n        this.count = count;\n    }\n}\n",
                        PACKAGE + "public class Shelf {\n    @Legacy\n"
                                + "    @org.springframework.transaction.annotation.Transactional\n"
                                + "    public void sell(Stock stock) {\n        stock.setCount(stock.getCount() - 1);\n    }\n}\n"),
                        List.of()),
                member("noFloatingPointMoney", g -> g.data().noFloatingPointMoney(), List.of(PACKAGE
                        + "public class Invoice {\n    @Legacy\n    private double price;\n\n"
                        + "    public double price() {\n        return price;\n    }\n}\n"), List.of()),
                member("noUnboundedWaits", g -> g.guardrails().noUnboundedWaits(), List.of(PACKAGE
                        + "public class Reports {\n    @Legacy\n"
                        + "    public String result(java.util.concurrent.Future<String> future) throws Exception {\n"
                        + "        return future.get();\n    }\n}\n"), List.of()),
                member("noRawThreads", g -> g.dependencies().noRawThreads(), List.of(PACKAGE
                        + "public class Jobs {\n    @Legacy\n    public void run(Runnable job) {\n"
                        + "        new Thread(job).start();\n    }\n}\n"), List.of()),
                member("noQueriesInLoops", g -> g.dependencies().noQueriesInLoops(), List.of(PACKAGE
                                + "public class Prices {\n    public String priceOf(String item) {\n"
                                + "        return java.sql.DriverManager.getLoginTimeout() + item;\n    }\n}\n",
                        PACKAGE + "public class Checkout {\n    private final Prices prices = null;\n\n    @Legacy\n"
                                + "    public String payAll(java.util.List<String> items) {\n        String all = \"\";\n"
                                + "        for (String item : items) {\n            all += prices.priceOf(item);\n        }\n"
                                + "        return all + item(items);\n    }\n\n"
                                + "    private String item(java.util.List<String> items) {\n        return items.get(0);\n    }\n}\n"),
                        List.of()),
                member("constructorsDoNoWork", g -> g.dependencies().constructorsDoNoWork(), List.of(PACKAGE
                        + "public class Catalog {\n    private final String text;\n\n    @Legacy\n"
                        + "    public Catalog(java.nio.file.Path file) throws java.io.IOException {\n"
                        + "        this.text = java.nio.file.Files.readString(file);\n    }\n\n"
                        + "    public String text() {\n        return text;\n    }\n}\n"), List.of()),
                member("interfacePolicy", g -> g.dependencies().interfacePolicy(InterfacePolicy.SERVICES), List.of(
                        STRIPE_CLIENT, "package shop;\n\npublic class Checkout {\n    @Legacy\n"
                                + "    private StripeClient client;\n\n    public String pay() {\n"
                                + "        return String.valueOf(client);\n    }\n}\n"), List.of()),
                member("valueTypesAreImmutable", g -> g.types().valueTypesAreImmutable(), List.of(PACKAGE
                        + "public class Tally {\n    @Legacy\n    private int total;\n\n    public void add(int amount) {\n"
                        + "        total += amount;\n    }\n}\n"), List.of()),
                member("servicesAreStateless", g -> g.types().servicesAreStateless(), List.of(PACKAGE
                        + "public class PaymentLog {\n    @Legacy\n    private int written;\n\n"
                        + "    public void write(String line) {\n        written++;\n"
                        + "        System.out.println(line + written);\n    }\n}\n"), List.of()),
                member("typedIdentifiers", g -> g.types().typedIdentifiers("shop.."), List.of(PACKAGE
                        + "public class Order {\n    @Legacy\n    private final String customerId = \"c1\";\n\n"
                        + "    public String customer() {\n        return customerId;\n    }\n}\n"), List.of()),
                member("typeDispatch", g -> g.types().typeDispatch(TypeDispatchPolicy.SEALED_ONLY), List.of(
                        "package shop;\n\npublic interface Animal {\n}\n",
                        "package shop;\n\npublic record Cat() implements Animal {\n}\n",
                        "package shop;\n\npublic record Dog() implements Animal {\n}\n",
                        "package shop;\n\npublic class Sounds {\n    @Legacy\n    public String sound(Animal animal) {\n"
                                + "        if (animal instanceof Cat) {\n            return \"meow\";\n"
                                + "        } else if (animal instanceof Dog) {\n            return \"woof\";\n        }\n"
                                + "        return \"\";\n    }\n}\n"), List.of()),
                member("noFlagArguments", g -> g.api().noFlagArguments(), List.of(PACKAGE
                        + "public class Printer {\n    @Legacy\n    public String print(String text, boolean upper) {\n"
                        + "        if (upper) {\n            return text.toUpperCase(java.util.Locale.ROOT);\n        }\n"
                        + "        return text;\n    }\n}\n"), List.of()),
                member("optionalUsage", g -> g.api().optionalUsage(), List.of(PACKAGE
                        + "public class Holder {\n    @Legacy\n"
                        + "    private final java.util.Optional<String> name = java.util.Optional.empty();\n\n"
                        + "    @Legacy\n    public String or(java.util.Optional<String> other) {\n"
                        + "        return name.or(() -> other).orElse(\"\");\n    }\n}\n"), List.of()),
                member("noCheckedExceptions", g -> g.api().noCheckedExceptions(), List.of(PACKAGE
                        + "public class Reader {\n    @Legacy\n    public String read() throws java.io.IOException {\n"
                        + "        return \"\";\n    }\n}\n"), List.of()),
                member("noEffectfulStaticMethods", g -> g.api().noEffectfulStaticMethods(), List.of(PACKAGE
                        + "public final class Disk {\n    private Disk() {\n    }\n\n    @Legacy\n"
                        + "    public static String read(java.nio.file.Path file) throws java.io.IOException {\n"
                        + "        return java.nio.file.Files.readString(file);\n    }\n}\n",
                        "package shop;\n\npublic class Runner {\n"
                                + "    public String run(java.nio.file.Path file) throws java.io.IOException {\n"
                                + "        return Disk.read(file);\n    }\n}\n"), List.of()),
                member("typedApis", g -> g.api().typedApis(), List.of(PACKAGE
                        + "public class Lookup {\n    @Legacy\n    public Object find(String key) {\n"
                        + "        return key;\n    }\n}\n"), List.of()),
                member("accessorsChangeNothing", g -> g.api().accessorsChangeNothing(), List.of(PACKAGE
                        + "public class Counter {\n    private int count;\n\n    @Legacy\n    public int getNext() {\n"
                        + "        return count++;\n    }\n}\n"), List.of()),
                member("adaptersStayThin", g -> g.architecture().adaptersStayThin("shop.."), List.of(
                        PACKAGE + "public class CheckoutPage {\n    @Legacy\n    public String show(int items, boolean member) {\n"
                                + "        if (items == 0) {\n            return \"empty\";\n        }\n"
                                + "        if (member) {\n            return \"member\";\n        }\n"
                                + "        return items > 10 ? \"bulk\" : \"regular\";\n    }\n}\n"), List.of()),
                member("noStringlyTypedChoices", g -> g.api().noStringlyTypedChoices(), List.of(
                        PACKAGE + "public class Fees {\n    @Legacy\n    public int fee(String delivery) {\n"
                                + "        return switch (delivery) {\n            case \"express\" -> 10;\n"
                                + "            case \"pickup\" -> 0;\n            case \"standard\" -> 5;\n"
                                + "            default -> -1;\n        };\n    }\n}\n"), List.of()),
                member("noStubs", g -> g.guardrails().noStubs(), List.of(PACKAGE
                        + "public class Cart {\n    @Legacy\n    public int total() {\n        // TODO apply discounts\n"
                        + "        return 0;\n    }\n}\n"), List.of()),
                member("noSuppressions", g -> g.guardrails().noSuppressions(), List.of(PACKAGE
                        + "public class Casts {\n    @Legacy\n    @SuppressWarnings(\"unchecked\")\n"
                        + "    public java.util.List<String> names(Object list) {\n"
                        + "        return (java.util.List<String>) list;\n    }\n}\n"), List.of()),
                member("testsAssertMeaningfully", g -> g.tests().testsAssertMeaningfully(), List.of(), List.of(
                        "package shop;\n\nclass CartTest {\n    @Legacy\n    @org.junit.jupiter.api.Test\n"
                                + "    void totals() {\n        new Object();\n    }\n}\n")),
                member("testsHaveNoLogic", g -> g.tests().testsHaveNoLogic(), List.of(), List.of(
                        PACKAGE + "class CartTest {\n    @Legacy\n    @org.junit.jupiter.api.Test\n    void totals() {\n"
                                + "        if (Math.abs(-1) > 0) {\n            org.assertj.core.api.Assertions.assertThat(1).isOne();\n"
                                + "        }\n    }\n}\n")),
                member("testsUseAFixedClock", g -> g.tests().testsUseAFixedClock(), List.of(), List.of(nondeterministicTest(
                        "java.time.LocalDate.now()"))),
                member("testsUseSeededRandomness", g -> g.tests().testsUseSeededRandomness(), List.of(), List.of(
                        nondeterministicTest("new java.util.Random().nextInt()"))),
                member("testsDontReadTheEnvironment", g -> g.tests().testsDontReadTheEnvironment(), List.of(), List.of(
                        nondeterministicTest("System.getenv(\"REGION\")"))),
                member("testsDontSleep", g -> g.tests().testsDontSleep(), List.of(), List.of(PACKAGE + "class CartTest {\n"
                        + "    @Legacy\n    @org.junit.jupiter.api.Test\n    void totals() throws InterruptedException {\n"
                        + "        Thread.sleep(10);\n        org.assertj.core.api.Assertions.assertThat(1).isOne();\n"
                        + "    }\n}\n")),
                member("noDisabledTests", g -> g.tests().noDisabledTests(), List.of(), List.of(
                        "package shop;\n\nclass CartTest {\n    @Legacy\n    @org.junit.jupiter.api.Disabled\n"
                                + "    @org.junit.jupiter.api.Test\n    void totals() {\n"
                                + "        org.assertj.core.api.Assertions.assertThat(1).isOne();\n    }\n}\n")),
                member("testsCheckResults", g -> g.tests().testsCheckResults(), List.of(
                        "package shop;\n\npublic interface Prices {\n    long priceOf(String item);\n}\n"), List.of(
                        "package shop;\n\nimport static org.mockito.Mockito.mock;\nimport static org.mockito.Mockito.verify;\n\n"
                                + "class PricesTest {\n    private final Prices prices = mock(Prices.class);\n\n"
                                + "    @Legacy\n    @org.junit.jupiter.api.Test\n    void prices() {\n"
                                + "        verify(prices).priceOf(\"book\");\n    }\n}\n")),
                member("testsDontMockValues", g -> g.tests().testsDontMockValues(), List.of(
                        "package shop;\n\npublic record Money(long cents) {\n}\n"), List.of(
                        "package shop;\n\nclass MoneyTest {\n    @Legacy\n    @org.mockito.Mock\n    private Money money;\n}\n")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void ignoringSkipsAnnotatedMembers(String name, Function<DesignGuard, DesignRule<?>> rule, List<String> sources,
                                       List<String> tests) {
        CompiledProject project = new CompiledProject(directory).release(release).add("Legacy", PACKAGE
                + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                + "public @interface Legacy {\n}\n");
        sources.forEach(source -> project.add(typeName(source), source));
        tests.forEach(source -> project.addTest(typeName(source), source));
        DesignRule<?> configured = rule.apply(project.guard("shop"));

        assertThat(configured.evaluate()).as("the fixture breaks %s", name).isNotEmpty();
        assertThat(configured.ignoring(LEGACY).evaluate()).isEmpty();
    }

    private static Arguments member(String name, Function<DesignGuard, DesignRule<?>> rule, List<String> sources,
                                    List<String> tests) {
        return Arguments.of(name, rule, sources, tests);
    }

    /** A test, annotated {@code @Legacy}, asserting on a nondeterministic expression. */
    private static String nondeterministicTest(String expression) {
        return PACKAGE + "class CartTest {\n    @Legacy\n    @org.junit.jupiter.api.Test\n    void totals() {\n"
                + "        org.assertj.core.api.Assertions.assertThat(" + expression + ").isNotNull();\n    }\n}\n";
    }

    private static String typeName(String source) {
        Matcher matcher = TYPE_NAME.matcher(source);
        if (!matcher.find()) {
            throw new IllegalArgumentException("no type in " + source);
        }
        return matcher.group(1);
    }

    /** A Spring web annotation, such as {@code RestController}. */
    private static String web(String name) {
        return "package org.springframework.web.bind.annotation;\n\n" + RUNTIME + "public @interface " + name + " {\n}\n";
    }
}
