package io.github.yoavgeva.designguard.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yoavgeva.designguard.CompiledProject;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedCodeTest {

    @TempDir
    private Path directory;

    @Test
    void codeTheBuildGeneratesIsAnalysedButNotReported() {
        DesignGuard guard = new CompiledProject(directory)
                .add("Money", "package shop;\n\npublic class Money {\n    private long cents;\n}\n")
                .add("OrderDto", "package shop;\n\npublic class OrderDto {\n    private String name;\n}\n")
                .add("OrderMapper", "package shop;\n\npublic interface OrderMapper {\n    OrderDto toDto(String order);\n}\n")
                .addGenerated("OrderMapperImpl", """
                        package shop;

                        public class OrderMapperImpl implements OrderMapper {
                            private int mapped;

                            @Override
                            public OrderDto toDto(String order) {
                                mapped++;
                                return new OrderDto();
                            }
                        }
                        """)
                .addGenerated("Stamps", """
                        package shop;

                        public class Stamps {
                            public String stamp() {
                                return System.getenv("STAMP");
                            }
                        }
                        """)
                .add("Ledger", """
                        package shop;

                        public class Ledger {
                            private final Stamps stamps;
                            private int entries;

                            public Ledger(Stamps stamps) {
                                this.stamps = stamps;
                            }

                            public String record() {
                                entries++;
                                return stamps.stamp() + entries;
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.types().valueTypesAreImmutable().evaluate()).extracting(Violation::message).containsExactly(
                "Money is a value, but its field 'cents' isn't final",
                "OrderDto is a value, but its field 'name' isn't final");
        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Ledger is service-like, but its field 'entries' isn't final");
    }
}
