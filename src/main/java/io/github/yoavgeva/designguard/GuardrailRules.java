package io.github.yoavgeva.designguard;

import io.github.yoavgeva.designguard.internal.AuthorizedReadsCheck;
import io.github.yoavgeva.designguard.internal.AuthorizedWritesCheck;
import io.github.yoavgeva.designguard.internal.CodeBase;
import io.github.yoavgeva.designguard.internal.ServiceTestsCheck;
import io.github.yoavgeva.designguard.internal.StubCheck;
import io.github.yoavgeva.designguard.internal.SwallowedErrorsCheck;
import io.github.yoavgeva.designguard.internal.UnboundedWaits;
import io.github.yoavgeva.designguard.internal.UnusedPublicCodeCheck;
import io.github.yoavgeva.designguard.internal.WidenedForTestsCheck;
import java.util.Set;

/** Rules that catch the shortcuts AI agents take: unfinished code, silenced warnings, tests that prove nothing. */
public final class GuardrailRules {

    private final CodeBase code;

    GuardrailRules(CodeBase code) {
        this.code = code;
    }

    /**
     * Fails on unfinished code: {@code TODO}/{@code FIXME} comments, methods that only throw
     * {@code UnsupportedOperationException}, and empty method bodies without a comment saying why.
     *
     * @return the rule
     */
    public BasicRule noStubs() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new StubCheck().check(codeBase.sources(), filter));
    }

    /**
     * Fails on warnings silenced instead of fixed, in production and test code.
     *
     * @return the rule
     * @see NoSuppressionsRule
     */
    public NoSuppressionsRule noSuppressions() {
        return new NoSuppressionsRule(code, RuleScope.DEFAULT, Set.of());
    }

    /**
     * Fails when a service-like class has no test class named {@code <Name>Test}, {@code <Name>Tests} or
     * {@code <Name>IT} in the test sources.
     *
     * @return the rule
     */
    public BasicRule serviceLikeClassesHaveTests() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new ServiceTestsCheck().check(codeBase, filter));
    }

    /**
     * Fails on package-private or protected methods and constructors that no production code calls but tests in
     * their package do: they were widened so tests could reach inside the class.
     *
     * @return the rule
     */
    public BasicRule noWidenedForTests() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new WidenedForTestsCheck().check(codeBase, filter));
    }

    /**
     * Fails on errors swallowed: a {@code catch} of {@code Exception}, {@code RuntimeException} or {@code Throwable}
     * that only logs, then returns a default such as {@code null} or carries on, and Reactor's {@code onErrorReturn},
     * {@code onErrorResume} and {@code CompletableFuture.exceptionally} turning every error into a default. Callers
     * can't tell the call failed. Handlers in a loop, around releasing a resource, of specific exceptions, or that
     * pass the error on, are fine.
     *
     * @return the rule
     */
    public BasicRule noSwallowedErrors() {
        return new BasicRule(code, RuleScope.DEFAULT,
                (codeBase, filter) -> new SwallowedErrorsCheck().check(codeBase.sources(), filter));
    }

    /**
     * Fails on request handlers that change data in a database with nothing checking that the user asking may: a
     * Spring MVC or JAX-RS handler whose calls surely write, without {@code @PreAuthorize}, {@code @Secured} or
     * {@code @RolesAllowed} on it or its class, without taking the current user, as an
     * {@code @AuthenticationPrincipal} or {@code Principal} parameter, and without calling code that checks
     * permissions. Handlers that URL rules in a security filter chain protect pass through no code designguard sees,
     * so leave them out with {@code excluding} or {@code ignoring}.
     *
     * @return the rule
     */
    public BasicRule writesAreAuthorized() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new AuthorizedWritesCheck().check(codeBase, filter));
    }

    /**
     * Fails on request handlers that read a record by a key the request sends with nothing checking that the user
     * asking may see it: what a {@code @PathVariable} or {@code @RequestParam} holds flows, through the project code it
     * is passed to, into a repository's {@code findById}, an {@code EntityManager}'s {@code find} or MyBatis-Plus's
     * {@code selectById}. A handler passes on the terms {@link #writesAreAuthorized()} reads, and a read made in a
     * method that checks the user itself passes. Many records are public by design, so this rule isn't recommended:
     * point it at the handlers that show private data.
     *
     * @return the rule
     */
    public BasicRule readsAreAuthorized() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new AuthorizedReadsCheck().check(codeBase, filter));
    }

    /**
     * Fails on waits with no limit: blocking calls such as {@code Future.get()}, {@code CompletableFuture.join()},
     * {@code CountDownLatch.await()} and Reactor's {@code block()} without a timeout, and HTTP clients and requests
     * built without one, as {@code new RestTemplate()} and {@code HttpClient.newHttpClient()} are. A wait that never
     * ends blocks its thread, and everything waiting on that thread. A limit the same method sets, such as
     * {@code orTimeout(...)} before {@code join()}, counts.
     *
     * @return the rule
     */
    public BasicRule noUnboundedWaits() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> UnboundedWaits.check().check(codeBase, filter));
    }

    /**
     * Fails on public types and methods nothing in the project uses. Meant for applications, where unused public
     * code is dead code; in a library it is the API.
     *
     * @return the rule
     */
    public BasicRule noUnusedPublicCode() {
        return new BasicRule(code, RuleScope.DEFAULT, (codeBase, filter) -> new UnusedPublicCodeCheck().check(codeBase, filter));
    }
}
