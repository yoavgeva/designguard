package io.github.yoavgeva.designguard.service;

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

    private List<Violation> violations(String source) {
        String typeName = source.lines()
                .filter(line -> line.startsWith("class "))
                .findFirst()
                .map(line -> line.split(" ")[1])
                .orElseThrow();
        new SourceTree(root).add(typeName, source);
        return new FlagArgumentCheck().check(JavaSources.read(List.of(root)), ClassFilter.ALL);
    }
}
