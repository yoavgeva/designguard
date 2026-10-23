package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import io.github.yoavgeva.designguard.view.TextReport;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every rule reports the same violations, in the same order, each time it reads the same code. ArchUnit keeps a
 * class's calls and accesses in sets whose order changes with every import, so each read here imports the classes
 * anew. Besides the legacy shop, the code has the ties that order would decide: several effects on one line, two
 * singletons reached through methods of the same name, a service used in several members, and a covariant override,
 * whose bridge method shares the real method's name.
 */
class RepeatableResultsTest {

    private static final int READS = 6;

    @TempDir
    private static Path directory;

    private static CompiledProject project;

    @BeforeAll
    static void compile() {
        project = LegacyShop.write(new CompiledProject(directory))
                .add("Settings", """
                        package shop.legacy.ties;

                        public class Settings {
                            private final String region;

                            public Settings() {
                                this.region = System.getenv("REGION") + System.getProperty("zone") + System.lineSeparator();
                            }

                            public String region() {
                                return region;
                            }
                        }
                        """)
                .add("Counter", """
                        package shop.legacy.ties;

                        public class Counter {
                            private int count;
                            private boolean dirty;

                            public int getCount() {
                                count = 0; dirty = false;
                                return count;
                            }

                            public boolean dirty() {
                                return dirty;
                            }
                        }
                        """)
                .add("Registry", singleton("Registry", "REGISTRY"))
                .add("Cache", singleton("Cache", "CACHE"))
                .add("Lookups", """
                        package shop.legacy.ties;

                        public class Lookups {
                            private final Registry registry;

                            public Lookups(Registry registry) {
                                this.registry = registry;
                            }

                            public String find(Cache cache, Registry other) {
                                return Registry.instance().name() + Cache.instance().name() + cache.name() + other.name()
                                        + registry.name();
                            }

                            public String again(Registry more) {
                                return more.name();
                            }
                        }
                        """)
                .add("Source", "package shop.legacy.ties;\n\npublic interface Source {\n    Object read();\n}\n")
                .add("Disk", """
                        package shop.legacy.ties;

                        final class Disk {
                            private Disk() {
                            }

                            static String load() {
                                return System.getenv("DATA");
                            }
                        }
                        """)
                .add("EnvSource", """
                        package shop.legacy.ties;

                        public class EnvSource implements Source {
                            private int reads;

                            @Override
                            public String read() {
                                reads++;
                                return Disk.load() + reads;
                            }
                        }
                        """);
    }

    @Test
    void everyRuleReportsTheSameEachTimeItReadsTheCode() {
        List<String> reports = IntStream.range(0, READS).mapToObj(read -> report()).toList();

        assertThat(reports).allSatisfy(report -> assertThat(report).isEqualTo(reports.get(0)));
    }

    @Test
    void ofSeveralEffectsOnOneLineTheFirstByNameIsReported() {
        assertThat(guard().dependencies().constructorsDoNoWork().evaluate()).extracting(Violation::message)
                .contains("Settings does work in its constructor through System.getProperty()");
    }

    private static String report() {
        DesignGuard guard = guard();
        TextReport text = new TextReport();
        return RuleFiltersContractTest.EVERY_RULE.stream()
                .map(rule -> rule.name() + ":\n" + text.render(rule.of().apply(guard).evaluate()))
                .collect(Collectors.joining("\n"));
    }

    private static DesignGuard guard() {
        return project.guard("shop.legacy");
    }

    private static String singleton(String name, String field) {
        return """
                package shop.legacy.ties;

                public final class %1$s {
                    private static final %1$s %2$s = new %1$s();

                    private %1$s() {
                    }

                    public static %1$s instance() {
                        return %2$s;
                    }

                    public String name() {
                        return System.getenv("%2$s");
                    }
                }
                """.formatted(name, field);
    }
}
