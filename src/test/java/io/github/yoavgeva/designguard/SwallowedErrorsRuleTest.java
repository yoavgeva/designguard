package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwallowedErrorsRuleTest {

    private static final String CATCH_FIX = "catch only the exceptions you expect and let the rest propagate, or rethrow it "
            + "wrapped in an unchecked exception";
    private static final String REACTOR_FIX = "handle only the errors you expect, as onErrorResume(NotFoundException.class, "
            + "...) does, and let the rest through";

    @TempDir
    private Path directory;

    @Test
    void catchAllHandlersThatPretendItWorkedFail() {
        DesignGuard guard = new CompiledProject(directory)
                .add("Mono", """
                        package reactor.core.publisher;

                        import java.util.function.Function;

                        public abstract class Mono<T> {
                            public static <T> Mono<T> empty() {
                                return null;
                            }

                            public abstract Mono<T> onErrorReturn(T fallback);

                            public abstract Mono<T> onErrorResume(Function<? super Throwable, ? extends Mono<? extends T>> fallback);

                            public abstract <E extends Throwable> Mono<T> onErrorResume(Class<E> type,
                                    Function<? super E, ? extends Mono<? extends T>> fallback);
                        }
                        """)
                .add("RateClient", """
                        package shop;

                        public interface RateClient {
                            String rate(String currency);

                            void close() throws Exception;

                            void failed(String currency, Exception error);
                        }
                        """)
                .add("Poller", """
                        package shop;

                        public class Poller implements Runnable {
                            private static final boolean WINDOWS;

                            static {
                                boolean windows = false;
                                try {
                                    windows = System.getProperty("os.name").startsWith("Windows");
                                } catch (Exception e) {
                                }
                                WINDOWS = windows;
                            }

                            @Override
                            public void run() {
                                try {
                                    System.out.println(WINDOWS);
                                } catch (Exception e) {
                                    System.err.println(e);
                                }
                            }
                        }
                        """)
                .add("Rates", """
                        package shop;

                        import java.util.List;
                        import java.util.concurrent.CompletableFuture;
                        import java.util.logging.Logger;
                        import reactor.core.publisher.Mono;

                        public class Rates {
                            private static final Logger LOG = Logger.getLogger("rates");

                            private final RateClient client;

                            public Rates(RateClient client) {
                                this.client = client;
                            }

                            public String rate(String currency) {
                                try {
                                    return client.rate(currency);
                                } catch (Exception e) {
                                    LOG.warning("no rate for " + currency + ": " + e);
                                    return null;
                                }
                            }

                            public void refresh(String currency) {
                                try {
                                    client.rate(currency);
                                } catch (RuntimeException e) {
                                }
                            }

                            public List<String> history(String currency) {
                                try {
                                    return List.of(client.rate(currency));
                                } catch (Throwable t) {
                                    return List.of();
                                }
                            }

                            public String rateOrDefault(String currency) {
                                try {
                                    return client.rate(currency);
                                } catch (IllegalArgumentException e) {
                                    return "1.0";
                                }
                            }

                            public String rateOrFail(String currency) {
                                try {
                                    return client.rate(currency);
                                } catch (Exception e) {
                                    throw new IllegalStateException("no rate for " + currency, e);
                                }
                            }

                            public String rateOrRecord(String currency) {
                                try {
                                    return client.rate(currency);
                                } catch (Exception e) {
                                    client.failed(currency, e);
                                    return null;
                                }
                            }

                            public void refreshAll(List<String> currencies) {
                                for (String currency : currencies) {
                                    try {
                                        client.rate(currency);
                                    } catch (Exception e) {
                                        LOG.warning(e.getMessage());
                                    }
                                }
                            }

                            public void close() {
                                try {
                                    client.close();
                                } catch (Exception e) {
                                    LOG.fine("already closed");
                                }
                            }

                            public void refreshEach(List<String> currencies) {
                                currencies.forEach(currency -> {
                                    try {
                                        client.rate(currency);
                                    } catch (Exception e) {
                                        LOG.warning(e.getMessage());
                                    }
                                });
                            }

                            public void refreshQuietly(String currency) {
                                try {
                                    client.rate(currency);
                                } catch (Exception e) {
                                    LOG.fine("no rate");
                                }
                            }

                            public String rateOr(String currency, String defaultValue) {
                                try {
                                    return client.rate(currency);
                                } catch (Exception e) {
                                    return defaultValue;
                                }
                            }

                            public boolean store(String currency) {
                                try {
                                    client.rate(currency);
                                    return true;
                                } catch (Exception e) {
                                    return false;
                                }
                            }

                            public boolean isQuoted(String currency) {
                                try {
                                    return client.rate(currency) != null;
                                } catch (Exception e) {
                                    return false;
                                }
                            }

                            public void dispose() {
                                try {
                                    client.rate("flush");
                                } catch (Exception e) {
                                    LOG.warning("couldn't flush on the way out");
                                }
                            }

                            public String echo(String currency) {
                                try {
                                    return client.rate(currency);
                                } catch (Exception e) {
                                    return currency;
                                }
                            }

                            public Mono<String> live(Mono<String> rate) {
                                return rate.onErrorReturn("1.0");
                            }

                            public Mono<String> liveOrEmpty(Mono<String> rate) {
                                return rate.onErrorResume(e -> Mono.empty());
                            }

                            public Mono<String> liveOrNotFound(Mono<String> rate) {
                                return rate.onErrorResume(IllegalArgumentException.class, e -> Mono.empty());
                            }

                            public CompletableFuture<String> later(CompletableFuture<String> rate) {
                                return rate.exceptionally(e -> null);
                            }

                            public CompletableFuture<String> laterOrFail(CompletableFuture<String> rate) {
                                return rate.exceptionally(e -> {
                                    throw new IllegalStateException(e);
                                });
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.guardrails().noSwallowedErrors().evaluate())
                .extracting(Violation::line, Violation::message, Violation::fix)
                .containsExactly(
                        tuple(20, "Rates.rate() catches every exception and returns null, so callers can't tell it failed",
                                CATCH_FIX),
                        tuple(29, "Rates.refresh() catches every exception and carries on, so callers can't tell it failed",
                                CATCH_FIX),
                        tuple(36, "Rates.history() catches every exception and returns List.of(), so callers can't tell it "
                                + "failed", CATCH_FIX),
                        tuple(138, "Rates.echo() catches every exception and returns currency, so callers can't tell it "
                                + "failed", CATCH_FIX),
                        tuple(144, "Rates.live() turns every error into a fallback with onErrorReturn, so subscribers can't "
                                + "tell it failed", REACTOR_FIX),
                        tuple(148, "Rates.liveOrEmpty() turns every error into a fallback with onErrorResume, so subscribers "
                                + "can't tell it failed", REACTOR_FIX),
                        tuple(156, "Rates.later() turns every error into a fallback with exceptionally, so callers can't tell "
                                + "it failed", "handle only the exceptions you expect in exceptionally, and rethrow the rest"));
    }
}
