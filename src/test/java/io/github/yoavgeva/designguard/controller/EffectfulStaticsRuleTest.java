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
class EffectfulStaticsRuleTest {

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void staticMethodsOthersCallTouchNothing() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Sources", """
                        package shop;

                        import java.io.IOException;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.List;

                        public final class Sources {
                            private Sources() {
                            }

                            public static List<String> read(Path file) throws IOException {
                                return Files.readAllLines(file);
                            }

                            public static int count(Path file) throws IOException {
                                return read(file).size();
                            }
                        }
                        """)
                .add("Runner", """
                        package shop;

                        public class Runner {
                            public int run(java.nio.file.Path file) throws java.io.IOException {
                                return Sources.read(file).size();
                            }
                        }
                        """)
                .add("Clocks", """
                        package shop;

                        public final class Clocks {
                            private Clocks() {
                            }

                            public static long now() {
                                return System.currentTimeMillis();
                            }
                        }
                        """)
                .add("Formats", """
                        package shop;

                        public final class Formats {
                            private Formats() {
                            }

                            public static String money(long cents) {
                                return cents / 100 + "." + cents % 100;
                            }
                        }
                        """)
                .add("Invoice", """
                        package shop;

                        public class Invoice {
                            public String stamp(long cents) {
                                return Formats.money(cents) + "@" + Clocks.now();
                            }
                        }
                        """)
                .add("Storage", "package shop;\n\npublic interface Storage {\n    void save(String text);\n}\n")
                .add("DiskStorage", """
                        package shop;

                        public final class DiskStorage implements Storage {
                            private final java.nio.file.Path file;

                            public DiskStorage(java.nio.file.Path file) {
                                this.file = file;
                            }

                            @Override
                            public void save(String text) {
                                try {
                                    System.out.println(text + Sources.read(file));
                                } catch (java.io.IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            }
                        }
                        """)
                .add("Storages", """
                        package shop;

                        public final class Storages {
                            private Storages() {
                            }

                            public static void saveAll(Storage storage, java.util.List<String> texts) {
                                texts.forEach(storage::save);
                            }

                            public static java.util.function.Supplier<String> lazyRegion() {
                                return () -> System.getenv("REGION");
                            }
                        }
                        """)
                .add("Audit", """
                        package shop;

                        public class Audit {
                            public void log(Storage storage, String line) {
                                Storages.saveAll(storage, java.util.List.of(line));
                                write(line + Storages.lazyRegion());
                            }

                            private static void write(String line) {
                                System.out.println(line);
                            }
                        }
                        """)
                .add("Config", """
                        package shop;

                        public final class Config {
                            private Config() {
                            }

                            public static String region() {
                                return System.getenv("REGION");
                            }
                        }
                        """)
                .add("App", """
                        package shop;

                        public final class App {
                            public static void main(String[] args) {
                                System.out.println(Config.region());
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.api().noEffectfulStaticMethods().evaluate()).extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(8, "Clocks.now() is static and touches the outside world, so callers like Invoice can't"
                                + " replace it in tests"),
                        tuple(13, "Sources.read() is static and touches the outside world, so callers like Runner can't"
                                + " replace it in tests"));
        assertThat(guard.api().noEffectfulStaticMethods().evaluate().get(0).fix())
                .isEqualTo("make it an instance method of a class that callers take as a constructor parameter");
    }
}
