package io.github.yoavgeva.designguard.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlagArgumentCheckTest {

    @TempDir
    private Path root;

    @Test
    void reportsABooleanParameterThatSelectsTheBehaviour() {
        assertThat(violations("""
                package shop;

                class Orders {
                    void save(String order, boolean async) {
                        if (async) {
                            queue(order);
                        } else {
                            write(order);
                        }
                    }

                    private void queue(String order) {
                        write(order);
                    }

                    private void write(String order) {
                        System.out.println(order);
                    }
                }
                """)).singleElement().satisfies(violation -> {
                    assertThat(violation.rule()).isEqualTo("no-flag-arguments");
                    assertThat(violation.line()).isEqualTo(4);
                    assertThat(violation.message())
                            .isEqualTo("Orders.save() branches on its boolean parameter 'async'");
                    assertThat(violation.fix())
                            .isEqualTo("split it into one method per behaviour, so callers don't pass true or false");
                });
    }

    @Test
    void allowsBooleansAnHttpRequestBinds() {
        assertThat(violations("""
                package shop;

                import org.springframework.web.bind.annotation.RequestParam;

                class OperationResource {
                    String list(@RequestParam(name = "eagerload", defaultValue = "true") boolean eagerload) {
                        if (eagerload) {
                            return "with labels";
                        }
                        return "plain";
                    }

                    @org.springframework.web.bind.annotation.GetMapping("/download")
                    String download(String name, Boolean delete) {
                        if (delete) {
                            return "deleted " + name;
                        }
                        return name;
                    }

                    String find(@jakarta.ws.rs.QueryParam("deep") boolean deep, boolean cached) {
                        if (deep) {
                            return "deep";
                        }
                        if (cached) {
                            return "cached";
                        }
                        return "shallow";
                    }
                }
                """)).extracting(Violation::message)
                .containsExactly("OperationResource.find() branches on its boolean parameter 'cached'");
    }

    @Test
    void reportsNegatedAndTernaryUses() {
        assertThat(violations("""
                package shop;

                class Report {
                    String render(String body, boolean dryRun, Boolean verbose) {
                        if (!dryRun) {
                            body = body.trim();
                        }
                        return verbose ? body + "!" : body;
                    }
                }
                """)).extracting(Violation::message).containsExactly(
                        "Report.render() branches on its boolean parameter 'dryRun'",
                        "Report.render() branches on its boolean parameter 'verbose'");
    }

    @Test
    void allowsBooleansUsedAsData() {
        assertThat(violations("""
                package shop;

                class Account {
                    private boolean active;

                    void setActive(boolean active) {
                        this.active = active;
                    }

                    boolean canPay(boolean hasFunds) {
                        return active && hasFunds;
                    }

                    private int bonus(boolean vip) {
                        if (vip) {
                            return 2;
                        }
                        return 1;
                    }

                    int total(boolean vip) {
                        return bonus(vip);
                    }
                }
                """)).isEmpty();
    }

    @Test
    void allowsBooleansThatOnlyPickAValue() {
        assertThat(violations("""
                package shop;

                class Booleans {
                    static int toInteger(boolean bool) {
                        return bool ? 1 : 0;
                    }

                    static String toString(boolean bool, String yes, String no) {
                        return bool ? yes : no;
                    }

                    void write(StringBuilder out, boolean value) {
                        if (value) {
                            out.append("true");
                        } else {
                            out.append("false");
                        }
                    }

                    int setBoolean(int holder, boolean flag) {
                        return flag ? holder | 1 : holder & ~1;
                    }

                    String label(boolean urgent) {
                        if (urgent) {
                            return send("now");
                        } else {
                            return queue("later");
                        }
                    }

                    String load(String id, boolean eager) {
                        return send(eager ? EAGER : LAZY) + id;
                    }

                    private String send(String when) {
                        return when;
                    }

                    private String queue(String when) {
                        return when;
                    }
                }
                """)).extracting(Violation::message).containsExactly(
                        "Booleans.label() branches on its boolean parameter 'urgent'",
                        "Booleans.load() branches on its boolean parameter 'eager'");
    }

    @Test
    void allowsPreconditionsThatOnlyThrowOnTheirBoolean() {
        assertThat(violations("""
                package shop;

                class Preconditions {
                    static void checkArgument(boolean condition, String message) {
                        if (!condition) {
                            throw new IllegalArgumentException(message);
                        }
                    }

                    static void checkState(boolean valid) {
                        if (!valid) throw new IllegalStateException();
                    }
                }
                """)).isEmpty();
    }

    @Test
    void allowsOverridesWhoseSignatureIsInherited() {
        assertThat(violations("""
                package shop;

                class Toggle implements java.util.function.Predicate<Boolean> {
                    @Override
                    public boolean test(Boolean on) {
                        if (on) {
                            return true;
                        }
                        return false;
                    }
                }
                """)).isEmpty();
    }

    @Test
    void allowsBooleansAnOverloadForOtherTypesTakesAsData() {
        assertThat(violations("""
                package shop;

                class Text {
                    private final StringBuilder out = new StringBuilder();

                    public Text append(boolean value) {
                        if (value) {
                            out.append("yes");
                        } else {
                            out.append("no").append('!');
                        }
                        return this;
                    }

                    public Text append(int value) {
                        out.append(value);
                        return this;
                    }

                    public int compare(boolean left, boolean right) {
                        if (left == right) {
                            return 0;
                        }
                        return left ? 1 : -1;
                    }

                    public int compare(long left, long right) {
                        return Long.compare(left, right);
                    }

                    public Text line(String text, boolean trim) {
                        if (trim) {
                            out.append(text.trim()).append('.');
                        } else {
                            out.append(text);
                        }
                        return this;
                    }

                    public Text line(String text) {
                        return line(text, false);
                    }

                    public Text pad(String text, boolean wide) {
                        return pad(text, wide ? Width.WIDE : Width.NARROW);
                    }

                    public Text pad(String text, Width width) {
                        out.append(width == Width.WIDE ? "  " + text : text);
                        return this;
                    }

                    public Text title(String text, boolean upper) {
                        if (upper) {
                            out.append(text.toUpperCase(java.util.Locale.ROOT));
                        } else {
                            out.append(text).append('.');
                        }
                        return this;
                    }

                    public Text title(StringBuilder text, String suffix) {
                        out.append(text).append(suffix);
                        return this;
                    }

                    enum Width { WIDE, NARROW }
                }
                """)).extracting(Violation::message).containsExactly(
                        "Text.line() branches on its boolean parameter 'trim'",
                        "Text.pad() branches on its boolean parameter 'wide'",
                        "Text.title() branches on its boolean parameter 'upper'");
    }

    private List<Violation> violations(String source) {
        String typeName = source.lines()
                .filter(line -> line.startsWith("class "))
                .findFirst()
                .map(line -> line.split(" ")[1])
                .orElseThrow();
        new SourceTree(root).add(typeName, source);
        return new FlagArgumentCheck().check(SourceReader.read(List.of(root)), ClassFilter.ALL);
    }
}
