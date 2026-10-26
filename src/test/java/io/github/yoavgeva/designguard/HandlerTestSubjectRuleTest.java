package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;

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
class HandlerTestSubjectRuleTest {

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void aHandlerTheProjectPicksByTypeRunsOnTheValuesTheTestPasses() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Serializer", "package shop.json;\n\npublic abstract class Serializer<T> {\n"
                        + "    public abstract String write(T value);\n}\n")
                .add("KeyReader", "package shop.json;\n\npublic abstract class KeyReader {\n"
                        + "    protected abstract Object read(String key);\n\n"
                        + "    public final Object readKey(String key) {\n        return read(key);\n    }\n}\n")
                .add("MonthSerializer", "package shop.json;\n\npublic class MonthSerializer extends Serializer<java.time.Month> {\n"
                        + "    @Override\n    public String write(java.time.Month value) {\n        return value.name();\n    }\n}\n")
                .add("LocaleSerializer", "package shop.json;\n\npublic class LocaleSerializer extends Serializer<java.util.Locale> {\n"
                        + "    @Override\n    public String write(java.util.Locale value) {\n        return value.toLanguageTag();\n    }\n}\n")
                .add("DayOfWeekSerializer", "package shop.json;\n\n"
                        + "public class DayOfWeekSerializer extends Serializer<java.time.DayOfWeek> {\n"
                        + "    @Override\n    public String write(java.time.DayOfWeek value) {\n        return value.name();\n    }\n}\n")
                .add("ZoneKeyReader", "package shop.json;\n\npublic class ZoneKeyReader extends KeyReader {\n"
                        + "    @Override\n    protected java.time.ZoneId read(String key) {\n        return java.time.ZoneId.of(key);\n    }\n}\n")
                .add("Serializers", """
                        package shop.json;

                        import java.time.Month;
                        import java.time.ZoneId;
                        import java.util.Locale;

                        final class Serializers {
                            private Serializers() {
                            }

                            static String write(Object value) {
                                if (value instanceof Month) {
                                    return new MonthSerializer().write((Month) value);
                                }
                                if (value instanceof Locale) {
                                    return new LocaleSerializer().write((Locale) value);
                                }
                                return String.valueOf(value);
                            }

                            static Object readKey(Class<?> type, String key) {
                                return type == ZoneId.class ? new ZoneKeyReader().readKey(key) : key;
                            }
                        }
                        """)
                .add("Mapper", "package shop.json;\n\npublic class Mapper {\n"
                        + "    public String write(Object value) {\n        return Serializers.write(value);\n    }\n\n"
                        + "    public Object readKey(Class<?> type, String key) {\n        return Serializers.readKey(type, key);\n"
                        + "    }\n}\n")
                .addTest("MapperTestBase", "package shop.json;\n\npublic abstract class MapperTestBase {\n"
                        + "    protected final Mapper mapper = new Mapper();\n}\n")
                .addTest("MonthSerializerTest", """
                        package shop.json;

                        import java.time.Month;

                        class MonthSerializerTest extends MapperTestBase {
                            @org.junit.jupiter.api.Test
                            void writesTheName() {
                                org.assertj.core.api.Assertions.assertThat(mapper.write(Month.MAY)).isEqualTo("MAY");
                            }
                        }
                        """)
                .addTest("ZoneKeyReaderTest", """
                        package shop.json;

                        import java.time.ZoneId;

                        class ZoneKeyReaderTest {
                            @org.junit.jupiter.api.Test
                            void readsTheZone() {
                                org.assertj.core.api.Assertions.assertThat(new Mapper().readKey(ZoneId.class, "UTC"))
                                        .isEqualTo(ZoneId.of("UTC"));
                            }
                        }
                        """)
                .addTest("DayOfWeekSerializerTest", """
                        package shop.json;

                        import java.time.DayOfWeek;

                        class DayOfWeekSerializerTest extends MapperTestBase {
                            @org.junit.jupiter.api.Test
                            void writesTheName() {
                                org.assertj.core.api.Assertions.assertThat(mapper.write(DayOfWeek.MONDAY)).isEqualTo("MONDAY");
                            }
                        }
                        """)
                .addTest("LocaleSerializerTest", """
                        package shop.json;

                        class LocaleSerializerTest extends MapperTestBase {
                            @org.junit.jupiter.api.Test
                            void writesTheName() {
                                org.assertj.core.api.Assertions.assertThat(mapper.write(java.time.Month.MAY)).isEqualTo("MAY");
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.tests().testsUseTheirSubject().evaluate()).extracting(Violation::message).containsExactly(
                "DayOfWeekSerializerTest never uses DayOfWeekSerializer, the class it's named after",
                "LocaleSerializerTest never uses LocaleSerializer, the class it's named after");
    }
}
