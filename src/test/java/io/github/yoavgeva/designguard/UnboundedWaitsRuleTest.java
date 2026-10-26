package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs once for each Java version's class files: see {@link CompiledProject#releases()}. */
@ParameterizedClass(name = "class files for Java {0}")
@MethodSource("io.github.yoavgeva.designguard.CompiledProject#releases")
class UnboundedWaitsRuleTest {

    private static final String SHOP = "shop";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void waitsAndClientsWithoutALimitFail() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Mono", """
                        package reactor.core.publisher;

                        public abstract class Mono<T> {
                            public abstract T block();

                            public abstract T block(java.time.Duration timeout);

                            public abstract Mono<T> timeout(java.time.Duration timeout);
                        }
                        """)
                .add("RestTemplate", """
                        package org.springframework.web.client;

                        public class RestTemplate {
                            public RestTemplate() {
                            }

                            public RestTemplate(Object requestFactory) {
                            }
                        }
                        """)
                .add("Reports", """
                        package shop;

                        import java.time.Duration;
                        import java.util.concurrent.CompletableFuture;
                        import java.util.concurrent.CountDownLatch;
                        import java.util.concurrent.Future;
                        import java.util.concurrent.TimeUnit;
                        import reactor.core.publisher.Mono;

                        public class Reports {
                            public String result(Future<String> future) throws Exception {
                                return future.get();
                            }

                            public String resultInTime(Future<String> future) throws Exception {
                                return future.get(5, TimeUnit.SECONDS);
                            }

                            public String joined(CompletableFuture<String> future) {
                                return future.join();
                            }

                            public String joinedInTime(CompletableFuture<String> future) {
                                return future.orTimeout(5, TimeUnit.SECONDS).join();
                            }

                            public void ready(CountDownLatch latch) throws InterruptedException {
                                latch.await();
                            }

                            public boolean readyInTime(CountDownLatch latch) throws InterruptedException {
                                return latch.await(5, TimeUnit.SECONDS);
                            }

                            public String blocked(Mono<String> report) {
                                return report.block();
                            }

                            public String blockedInTime(Mono<String> report) {
                                return report.block(Duration.ofSeconds(5));
                            }

                            public String timedOut(Mono<String> report) {
                                return report.timeout(Duration.ofSeconds(5)).block();
                            }

                            public String afterDone(Future<String> future) throws Exception {
                                return future.isDone() ? future.get() : null;
                            }
                        }
                        """)
                .add("Clients", """
                        package shop;

                        import java.io.IOException;
                        import java.io.InputStream;
                        import java.net.URI;
                        import java.net.URL;
                        import java.net.URLConnection;
                        import java.net.http.HttpClient;
                        import java.net.http.HttpRequest;
                        import java.time.Duration;
                        import org.springframework.web.client.RestTemplate;

                        public class Clients {
                            public RestTemplate rest() {
                                return new RestTemplate();
                            }

                            public HttpClient http() {
                                return HttpClient.newHttpClient();
                            }

                            public HttpClient httpInTime() {
                                return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                            }

                            public HttpRequest request(URI uri) {
                                return HttpRequest.newBuilder(uri).build();
                            }

                            public HttpRequest requestInTime(URI uri) {
                                return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build();
                            }

                            public InputStream download(URL url) throws IOException {
                                return url.openStream();
                            }

                            public URLConnection open(URL url) throws IOException {
                                return url.openConnection();
                            }

                            public InputStream resource() throws IOException {
                                return Clients.class.getResource("/prices.txt").openStream();
                            }

                            public InputStream inJar(java.nio.file.Path jar) throws IOException {
                                return new URL("jar:" + jar.toUri().toURL() + "!/prices.txt").openStream();
                            }

                            public URLConnection connect(URL url) throws IOException {
                                URLConnection connection = url.openConnection();
                                connection.setConnectTimeout(5000);
                                connection.setReadTimeout(5000);
                                return connection;
                            }
                        }
                        """)
                .add("Downloads", """
                        package shop;

                        import java.io.IOException;
                        import java.io.InputStream;
                        import java.net.URL;
                        import java.net.URLConnection;

                        public class Downloads {
                            public InputStream fetch(URL url) throws IOException {
                                URLConnection connection = url.openConnection();
                                configure(connection);
                                return connection.getInputStream();
                            }

                            private static void configure(URLConnection connection) {
                                connection.setConnectTimeout(5000);
                                connection.setReadTimeout(5000);
                            }
                        }
                        """)
                .add("Resources", """
                        package shop;

                        import java.io.IOException;
                        import java.io.InputStream;
                        import java.net.URL;

                        public class Resources {
                            public InputStream prices() throws IOException {
                                return resourceUrl("/prices.txt").openStream();
                            }

                            private static URL resourceUrl(String name) {
                                return Resources.class.getResource(name);
                            }
                        }
                        """)
                .add("Handed", """
                        package shop;

                        import java.io.IOException;
                        import java.io.InputStream;
                        import java.net.URL;
                        import java.net.URLConnection;

                        public class Handed {
                            public interface Configurator {
                                void configure(URLConnection connection);
                            }

                            public InputStream fetch(URL url, Configurator configurator) throws IOException {
                                URLConnection connection = url.openConnection();
                                configurator.configure(connection);
                                return connection.getInputStream();
                            }
                        }
                        """)
                .add("ClassFiles", """
                        package shop;

                        import java.io.IOException;
                        import java.io.InputStream;
                        import java.net.URI;
                        import java.util.function.Supplier;

                        public class ClassFiles {
                            public byte[] read(Supplier<URI> location) throws IOException {
                                try (InputStream in = location.get().toURL().openStream()) {
                                    return in.readAllBytes();
                                }
                            }
                        }
                        """)
                .add("Fetcher", """
                        package shop;

                        import java.io.IOException;
                        import java.io.InputStream;
                        import java.net.URL;
                        import java.net.URLConnection;

                        public class Fetcher {
                            public InputStream fetch(URL url) throws IOException {
                                URLConnection connection = url.openConnection();
                                connection.connect();
                                return connection.getInputStream();
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.guardrails().noUnboundedWaits().evaluate())
                .extracting(Violation::message, Violation::fix)
                .containsExactly(
                        tuple("Clients waits with no limit through new RestTemplate()",
                                "build it with a request factory that sets connect and read timeouts, as "
                                        + "RestTemplateBuilder's connectTimeout and readTimeout do"),
                        tuple("Clients waits with no limit through HttpClient.newHttpClient()",
                                "build it with HttpClient.newBuilder().connectTimeout(...), and give each request a timeout"),
                        tuple("Clients waits with no limit through HttpRequest.Builder.build()",
                                "give the request a timeout, as HttpRequest.newBuilder(uri).timeout(...) does"),
                        tuple("Clients waits with no limit through URL.openStream()",
                                "open a connection, and set its connect and read timeouts before reading"),
                        tuple("Fetcher waits with no limit through URL.openConnection()",
                                "set the connection's connect and read timeouts before reading"),
                        tuple("Reports waits with no limit through Future.get()",
                                "pass a timeout, as get(5, TimeUnit.SECONDS) does, and handle the TimeoutException"),
                        tuple("Reports waits with no limit through CompletableFuture.join()",
                                "add a limit with orTimeout(...) before joining, and handle the TimeoutException"),
                        tuple("Reports waits with no limit through CountDownLatch.await()",
                                "pass a timeout, as await(5, TimeUnit.SECONDS) does, and handle running out of time"),
                        tuple("Reports waits with no limit through Mono.block()",
                                "pass a timeout, as block(Duration.ofSeconds(5)) does, or add timeout(...) before it"));
    }
}
