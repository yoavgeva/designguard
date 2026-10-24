package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which classes count as service-like, seen through {@code servicesAreStateless}, which reports service-like classes
 * with state. Runs once for each Java version's class files: see {@link CompiledProject#releases()}.
 */
@ParameterizedClass(name = "class files for Java {0}")
@MethodSource("io.github.yoavgeva.designguard.CompiledProject#releases")
class ServiceLikeClassesRuleTest {

    private static final String SHOP = "shop";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void collaboratorsACallIsGivenAreTheCallersButStaticOnesAreTheClasss() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Storage", "package shop;\n\npublic interface Storage {\n    int size();\n}\n")
                .add("DiskStorage", """
                        package shop;

                        public final class DiskStorage implements Storage {
                            @Override
                            public int size() {
                                return System.getenv("SIZE").length();
                            }
                        }
                        """)
                .add("Pricing", """
                        package shop;

                        public final class Pricing {
                            private int calls;

                            public int count(Storage storage) {
                                calls++;
                                return storage.size() + calls;
                            }
                        }
                        """)
                .add("Ticks", """
                        package shop;

                        public final class Ticks {
                            private static final Storage STORAGE = new DiskStorage();

                            private Ticks() {
                            }

                            public static int count() {
                                return STORAGE.size();
                            }
                        }
                        """)
                .add("Counter", """
                        package shop;

                        public final class Counter {
                            private int calls;

                            public int count() {
                                calls++;
                                return Ticks.count() + calls;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Counter is service-like, but its field 'calls' isn't final");
    }

    @Test
    void subclassesOfServicesAreServicesButSiblingsOfOneAreNot() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Storage", "package shop;\n\npublic interface Storage {\n    int size();\n}\n")
                .add("DiskStorage", """
                        package shop;

                        public final class DiskStorage implements Storage {
                            @Override
                            public int size() {
                                return System.getenv("SIZE").length();
                            }
                        }
                        """)
                .add("Synchronization", """
                        package shop;

                        public abstract class Synchronization {
                            protected final Storage storage;

                            protected Synchronization(Storage storage) {
                                this.storage = storage;
                            }
                        }
                        """)
                .add("CacheSynchronization", """
                        package shop;

                        public class CacheSynchronization extends Synchronization {
                            private int flushes;

                            public CacheSynchronization(Storage storage) {
                                super(storage);
                            }

                            public void flush() {
                                flushes++;
                            }
                        }
                        """)
                .add("Saver", """
                        package shop;

                        public class Saver {
                            public int save() {
                                return System.getenv("SAVES").length();
                            }
                        }
                        """)
                .add("QuickSaver", """
                        package shop;

                        public final class QuickSaver extends Saver {
                            private int saves;

                            public int saves() {
                                return ++saves;
                            }
                        }
                        """)
                .add("Shape", """
                        package shop;

                        public abstract class Shape {
                            public abstract int area();

                            public int twice() {
                                return area() * 2;
                            }
                        }
                        """)
                .add("EnvShape", """
                        package shop;

                        public final class EnvShape extends Shape {
                            @Override
                            public int area() {
                                return System.getenv("AREA").length();
                            }
                        }
                        """)
                .add("Square", """
                        package shop;

                        public final class Square extends Shape {
                            private int side;

                            @Override
                            public int area() {
                                side++;
                                return side * side;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "CacheSynchronization is service-like, but its field 'flushes' isn't final",
                "QuickSaver is service-like, but its field 'saves' isn't final");
    }

    @Test
    void functionalInterfacesPassBehaviourAsDataLikeJavaUtilFunction() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Check", "package shop;\n\n@FunctionalInterface\npublic interface Check {\n"
                        + "    boolean test(String value);\n}\n")
                .add("EnvCheck", """
                        package shop;

                        public final class EnvCheck implements Check {
                            private int calls;

                            @Override
                            public boolean test(String value) {
                                calls++;
                                return System.getenv(value) != null && calls > 0;
                            }
                        }
                        """)
                .add("Matcher", """
                        package shop;

                        public final class Matcher {
                            private final Check check;
                            private int matches;

                            public Matcher(Check check) {
                                this.check = check;
                            }

                            public boolean matches(String value) {
                                if (check.test(value)) {
                                    matches++;
                                }
                                return matches > 0;
                            }
                        }
                        """)
                .add("Storage", "package shop;\n\npublic interface Storage {\n    int size();\n}\n")
                .add("DiskStorage", """
                        package shop;

                        public final class DiskStorage implements Storage {
                            @Override
                            public int size() {
                                return System.getenv("SIZE").length();
                            }
                        }
                        """)
                .add("Cache", """
                        package shop;

                        public final class Cache {
                            private final Storage storage;
                            private int hits;

                            public Cache(Storage storage) {
                                this.storage = storage;
                            }

                            public int size() {
                                hits++;
                                return storage.size() + hits;
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Cache is service-like, but its field 'hits' isn't final",
                "EnvCheck is service-like, but its field 'calls' isn't final");
    }

    @Test
    void persistentClassesAreDataEvenWhenTheyReadTheClock() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Entity", "package shop;\n\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface Entity {\n}\n")
                .add("Visit", """
                        package shop;

                        import java.time.Instant;
                        import java.time.LocalDate;

                        @Entity
                        public class Visit {
                            private LocalDate date;
                            private Instant created;

                            public Visit() {
                                this.date = LocalDate.now();
                            }

                            public void stamp() {
                                created = Instant.now();
                            }
                        }
                        """)
                .add("Pet", """
                        package shop;

                        import java.util.LinkedHashSet;
                        import java.util.Set;

                        @Entity
                        public class Pet {
                            private String name;
                            private final Set<Visit> visits = new LinkedHashSet<>();

                            public void addVisit(Visit visit) {
                                visits.add(visit);
                            }
                        }
                        """)
                .add("Agenda", """
                        package shop;

                        import java.util.ArrayList;
                        import java.util.List;

                        public final class Agenda {
                            private final List<Visit> visits = new ArrayList<>();
                            private int booked;

                            public void book(Visit visit) {
                                visits.add(visit);
                                booked++;
                            }
                        }
                        """)
                .add("Stopwatch", """
                        package shop;

                        public final class Stopwatch {
                            private long started;

                            public void start() {
                                started = System.nanoTime();
                            }
                        }
                        """)
                .guard(SHOP);

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Stopwatch is service-like, but its field 'started' isn't final");
    }
}
