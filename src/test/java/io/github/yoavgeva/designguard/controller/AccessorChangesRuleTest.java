package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.CompiledProject;
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
class AccessorChangesRuleTest {

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void updatesThatKeepTheValueOrMakeANewOneCacheButUpdatesBuiltOnItChange() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Mailers", """
                        package shop;

                        import java.util.List;
                        import java.util.concurrent.atomic.AtomicInteger;
                        import java.util.concurrent.atomic.AtomicReference;
                        import java.util.stream.Stream;

                        public class Mailers {
                            private final AtomicReference<String[]> sender = new AtomicReference<>();
                            private final AtomicReference<String> lazy = new AtomicReference<>();
                            private final AtomicReference<List<String>> history = new AtomicReference<>(List.of());
                            private final AtomicReference<Integer> boxed = new AtomicReference<>(0);
                            private final AtomicInteger attempts = new AtomicInteger();

                            public String getSender(String config) {
                                return sender.updateAndGet(pair -> {
                                    if (pair != null && pair[0].equals(config)) {
                                        return pair;
                                    }
                                    return new String[] {config, "sender for " + config};
                                })[1];
                            }

                            public String getLazy() {
                                return lazy.updateAndGet(value -> value == null ? "made" : value);
                            }

                            public String getRefreshed() {
                                return lazy.updateAndGet(this::refresh);
                            }

                            private String refresh(String value) {
                                return value != null && value.startsWith("fresh") ? value : "fresh";
                            }

                            public List<String> getHistory() {
                                return history.updateAndGet(seen -> Stream.concat(seen.stream(), Stream.of("read")).toList());
                            }

                            public int getBoxed() {
                                return boxed.updateAndGet(count -> count >= 3 ? count : count + 1);
                            }

                            public int getAttempts() {
                                return attempts.updateAndGet(count -> count >= 3 ? count : count + 1);
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.api().accessorsChangeNothing().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(37, "Mailers.getHistory() changes the field 'history'"),
                        tuple(41, "Mailers.getBoxed() changes the field 'boxed'"),
                        tuple(45, "Mailers.getAttempts() changes the field 'attempts'"));
    }

    @Test
    void aReaderTellingIfInputIsLeftMayReadAhead() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("CharacterReader", """
                        package shop;

                        import java.io.IOException;
                        import java.io.Reader;
                        import java.io.UncheckedIOException;

                        public class CharacterReader {
                            private final Reader reader;
                            private final char[] buffer = new char[64];
                            private int length;
                            private int position;

                            public CharacterReader(Reader reader) {
                                this.reader = reader;
                            }

                            public boolean isEmpty() {
                                fill();
                                return position >= length;
                            }

                            private void fill() {
                                if (position < length) {
                                    return;
                                }
                                try {
                                    length = reader.read(buffer);
                                    position = 0;
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }
                        }
                        """)
                .add("Basket", """
                        package shop;

                        public class Basket {
                            private int checks;

                            public boolean isEmpty() {
                                checks++;
                                return checks > 3;
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.api().accessorsChangeNothing().evaluate()).extracting(Violation::message)
                .containsExactly("Basket.isEmpty() changes the field 'checks'");
    }
}
