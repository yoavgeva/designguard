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
class StatelessServicesRuleTest {

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void fieldsAContainerInjectsAreCollaboratorsToTakeInTheConstructor() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Autowired", "package org.springframework.beans.factory.annotation;\n\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface Autowired {\n}\n")
                .add("Value", "package org.springframework.beans.factory.annotation;\n\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface Value {\n    String value();\n}\n")
                .add("Storage", "package shop;\n\npublic interface Storage {\n    void save(String text);\n}\n")
                .add("Accounts", """
                        package shop;

                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.beans.factory.annotation.Value;

                        public class Accounts {
                            @Autowired
                            private Storage storage;
                            @Value("${shop.region}")
                            private String region;
                            private int opened;

                            public void open(String name) {
                                opened++;
                                storage.save(name + region + System.getenv("SHOP"));
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message, Violation::fix)
                .containsExactly(
                        tuple("Accounts is service-like, but its field 'opened' isn't final", "keep state out of services:"
                                + " pass it in and return it as values, or keep it in a store the service calls"),
                        tuple("Accounts is service-like, but its injected field 'region' isn't final",
                                "take it as a constructor parameter and make the field final"),
                        tuple("Accounts is service-like, but its injected field 'storage' isn't final",
                                "take it as a constructor parameter and make the field final"));
    }
}
