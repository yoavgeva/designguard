package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds waits with no limit: a blocking call that waits as long as the other side takes, forever if it hangs, and
 * clients that make such calls. Blocking waits are {@code Future.get()}, {@code CompletableFuture.join()},
 * {@code CountDownLatch.await()}, {@code CyclicBarrier.await()}, {@code Thread.join()}, and Reactor's
 * {@code block()}, {@code blockFirst()} and {@code blockLast()}, without a timeout. Clients are {@code new RestTemplate()},
 * {@code HttpClient.newHttpClient()}, an {@code HttpClient} or {@code HttpRequest} built without a timeout, and
 * {@code URL.openStream()} and {@code URL.openConnection()} without a read timeout. A limit the same method sets
 * counts: {@code orTimeout(...)} before {@code join()}, {@code timeout(...)} before {@code block()},
 * {@code connectTimeout(...)} or {@code timeout(...)} on the builder, and {@code setReadTimeout(...)} on the connection.
 * Some calls don't wait at all: {@code get()} after {@code isDone()}, a URL connection only opened and handed on,
 * with no {@code connect()} or {@code getInputStream()} in the method, and a URL to a classpath resource or a local
 * file, as {@code getResource(...)} and {@code path.toUri().toURL()} make, in the method or a helper of its class that
 * returns the URL. A URL counts only when it comes from outside the code: the method takes a {@code URL} or {@code URI},
 * or makes one with {@code new URL(...)}, {@code new URI(...)} or {@code URI.create(...)}; one it gets from another
 * call, such as a class file's location, is the code's own. A class that sets read timeouts on its URL connections anywhere, as a helper configuring each one
 * does, limits them, and so does handing the connection to a call that takes it, which may configure it.
 */
public final class UnboundedWaits {

    private static final String COMPLETABLE = "java.util.concurrent.CompletableFuture";
    private static final String MONO = "reactor.core.publisher.Mono";
    private static final String FLUX = "reactor.core.publisher.Flux";
    private static final String CLIENT_BUILDER = "java.net.http.HttpClient$Builder";
    private static final String REQUEST_BUILDER = "java.net.http.HttpRequest$Builder";
    private static final String URL = "java.net.URL";
    private static final String URI = "java.net.URI";
    private static final String JOIN = "join";
    private static final String BUILD = "build";
    private static final String TIMEOUT_FIX = "pass a timeout, as %s does, and handle %s";
    private static final String URL_CONNECTION = "java.net.URLConnection";
    /** Calls on a URL connection that connect, and wait for the other side. */
    private static final Set<String> CONNECTING = Set.of("connect", "getInputStream", "getOutputStream", "getResponseCode",
            "getResponseMessage", "getContent", "getHeaderField", "getHeaderFields", "getContentType", "getContentLength",
            "getContentLengthLong");
    /** Calls that make a URL to something on this machine: a classpath resource or a file. */
    private static final Set<String> LOCAL_URLS = Set.of("getResource", "getResources", "getSystemResource",
            "getSystemResources", "toUri", "toURI");

    /** The calls a method makes to set a limit on a wait, by the waits they limit. */
    private static final Map<Predicate<CodeUnitAccessTarget>, Predicate<CodeUnitAccessTarget>> LIMITS = Map.of(
            target -> isOn(target, COMPLETABLE, Set.of(JOIN, "get")),
            target -> isOn(target, COMPLETABLE, Set.of("orTimeout", "completeOnTimeout")),
            target -> isOn(target, MONO, Set.of("block")) || isOn(target, FLUX, Set.of("blockFirst", "blockLast")),
            target -> isOn(target, MONO, Set.of("timeout")) || isOn(target, FLUX, Set.of("timeout")),
            target -> isOn(target, CLIENT_BUILDER, Set.of(BUILD)),
            target -> isOn(target, CLIENT_BUILDER, Set.of("connectTimeout")),
            target -> isOn(target, REQUEST_BUILDER, Set.of(BUILD)),
            target -> isOn(target, REQUEST_BUILDER, Set.of("timeout")),
            target -> isOn(target, URL, Set.of("openConnection")),
            UnboundedWaits::isReadTimeout);

    private UnboundedWaits() {
    }

    /**
     * The check.
     *
     * @return a check reporting each kind of wait with no limit once per class, at its first use
     */
    public static ForbiddenCallsCheck check() {
        return new ForbiddenCallsCheck("no-unbounded-waits", "waits with no limit", List.of(
                new ForbiddenCall(target -> withoutParameters(target, "get")
                        && target.getOwner().isAssignableTo("java.util.concurrent.Future"),
                        target -> String.format(TIMEOUT_FIX, "get(5, TimeUnit.SECONDS)", "the TimeoutException")),
                new ForbiddenCall(target -> withoutParameters(target, JOIN) && isOn(target, COMPLETABLE, Set.of(JOIN)),
                        target -> "add a limit with orTimeout(...) before joining, and handle the TimeoutException"),
                ForbiddenCall.withoutParameters("java.util.concurrent.CountDownLatch", "await",
                        String.format(TIMEOUT_FIX, "await(5, TimeUnit.SECONDS)", "running out of time")),
                ForbiddenCall.withoutParameters("java.util.concurrent.CyclicBarrier", "await",
                        String.format(TIMEOUT_FIX, "await(5, TimeUnit.SECONDS)", "the TimeoutException")),
                ForbiddenCall.withoutParameters("java.lang.Thread", JOIN,
                        "pass a timeout, as join(5000) does, and check whether the thread is still alive"),
                new ForbiddenCall(target -> withoutParameters(target, "block") && isOn(target, MONO, Set.of("block"))
                        || withoutParameters(target, target.getName()) && isOn(target, FLUX, Set.of("blockFirst", "blockLast")),
                        target -> "pass a timeout, as block(Duration.ofSeconds(5)) does, or add timeout(...) before it"),
                new ForbiddenCall(target -> withoutParameters(target, ForbiddenCall.CONSTRUCTOR)
                        && "org.springframework.web.client.RestTemplate".equals(target.getOwner().getName()),
                        target -> "build it with a request factory that sets connect and read timeouts, as "
                                + "RestTemplateBuilder's connectTimeout and readTimeout do"),
                ForbiddenCall.withoutParameters("java.net.http.HttpClient", "newHttpClient",
                        "build it with HttpClient.newBuilder().connectTimeout(...), and give each request a timeout"),
                new ForbiddenCall(target -> isOn(target, CLIENT_BUILDER, Set.of(BUILD)),
                        target -> "set connectTimeout(...) on the builder, and give each request a timeout"),
                new ForbiddenCall(target -> isOn(target, REQUEST_BUILDER, Set.of(BUILD)),
                        target -> "give the request a timeout, as HttpRequest.newBuilder(uri).timeout(...) does"),
                ForbiddenCall.of(URL, Set.of("openStream"),
                        "open a connection, and set its connect and read timeouts before reading"),
                ForbiddenCall.of(URL, Set.of("openConnection"),
                        "set the connection's connect and read timeouts before reading")),
                (bytecode, access) -> limited(access));
    }

    private static boolean limited(JavaCodeUnitAccess<?> access) {
        CodeUnitAccessTarget target = access.getTarget();
        boolean url = URL.equals(target.getOwner().getName());
        return limitedWhereItWaits(access)
                || "get".equals(target.getName()) && callsAny(access, call -> "isDone".equals(call.getName())
                        && call.getOwner().isAssignableTo("java.util.concurrent.Future"))
                || url && (isLocal(access) || !fromOutside(access))
                || url && "openConnection".equals(target.getName()) && (!callsAny(access, UnboundedWaits::connects)
                        || callsAny(access, UnboundedWaits::takesAConnection)
                        || access.getOriginOwner().getCodeUnits().stream()
                                .anyMatch(unit -> unit.getCallsFromSelf().stream().anyMatch(call -> isReadTimeout(call.getTarget()))));
    }

    /** Whether the URL is local: made by the method, or a helper of its class that returns it, from a resource or file. */
    private static boolean isLocal(JavaCodeUnitAccess<?> access) {
        return callsAny(access, call -> LOCAL_URLS.contains(call.getName()))
                || access.getOrigin().getCallsFromSelf().stream()
                        .filter(call -> call.getTargetOwner().equals(access.getOriginOwner())
                                && URL.equals(call.getTarget().getRawReturnType().getName()))
                        .flatMap(call -> call.getTarget().resolveMember().stream())
                        .anyMatch(helper -> helper.getCallsFromSelf().stream()
                                .anyMatch(call -> LOCAL_URLS.contains(call.getName())));
    }

    /** Whether the method takes a URL or URI, or makes one from a string, as an address the caller or user chose. */
    private static boolean fromOutside(JavaCodeUnitAccess<?> access) {
        return access.getOrigin().getRawParameterTypes().stream()
                        .anyMatch(type -> Set.of(URL, URI).contains(type.getName()))
                || callsAny(access, call -> Set.of(URL, URI).contains(call.getOwner().getName())
                        && (ForbiddenCall.CONSTRUCTOR.equals(call.getName()) || "create".equals(call.getName())));
    }

    /** A call other than the connection's own that takes a URL connection, which may configure it. */
    private static boolean takesAConnection(CodeUnitAccessTarget target) {
        return !target.getOwner().isAssignableTo(URL_CONNECTION) && target.getRawParameterTypes().stream()
                .anyMatch(type -> type.isAssignableTo(URL_CONNECTION));
    }

    private static boolean callsAny(JavaCodeUnitAccess<?> access, Predicate<CodeUnitAccessTarget> matching) {
        return access.getOrigin().getCallsFromSelf().stream().anyMatch(call -> matching.test(call.getTarget()));
    }

    private static boolean connects(CodeUnitAccessTarget target) {
        return CONNECTING.contains(target.getName()) && target.getOwner().isAssignableTo(URL_CONNECTION);
    }

    private static boolean isReadTimeout(CodeUnitAccessTarget target) {
        return "setReadTimeout".equals(target.getName()) && target.getOwner().isAssignableTo(URL_CONNECTION);
    }

    /** Whether the method that waits sets a limit on that kind of wait, as {@code orTimeout(...)} before {@code join()}. */
    private static boolean limitedWhereItWaits(JavaCodeUnitAccess<?> access) {
        CodeUnitAccessTarget target = access.getTarget();
        return LIMITS.entrySet().stream()
                .filter(limit -> limit.getKey().test(target))
                .anyMatch(limit -> access.getOrigin().getCallsFromSelf().stream()
                        .anyMatch(call -> limit.getValue().test(call.getTarget())));
    }

    private static boolean isOn(CodeUnitAccessTarget target, String owner, Set<String> names) {
        return owner.equals(target.getOwner().getName()) && names.contains(target.getName());
    }

    private static boolean withoutParameters(CodeUnitAccessTarget target, String name) {
        return name.equals(target.getName()) && target.getRawParameterTypes().isEmpty();
    }
}
