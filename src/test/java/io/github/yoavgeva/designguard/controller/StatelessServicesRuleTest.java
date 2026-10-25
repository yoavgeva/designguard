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

    @Test
    void objectsAFrameworkCreatesForEachUseHoldTheirOwnState() {
        String annotation = "package %s;\n\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                + "public @interface %s {\n    String name() default \"\";\n}\n";
        String command = """
                package shop;

                @%s
                public class %s {
                    private String name;
                    private int written;

                    public void run() throws java.io.IOException {
                        written++;
                        java.nio.file.Files.writeString(java.nio.file.Path.of(name), "alias");
                    }
                }
                """;
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("CommandDefinition", annotation.formatted("org.aesh.command", "CommandDefinition"))
                .add("CommandLine", "package picocli;\n\npublic final class CommandLine {\n"
                        + "    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "    public @interface Command {\n        String name() default \"\";\n    }\n}\n")
                .add("ServerEndpoint", annotation.formatted("jakarta.websocket.server", "ServerEndpoint"))
                .add("RequestScoped", annotation.formatted("jakarta.enterprise.context", "RequestScoped"))
                .add("AliasAdd", command.formatted("org.aesh.command.CommandDefinition(name = \"add\")", "AliasAdd"))
                .add("Export", command.formatted("picocli.CommandLine.Command(name = \"export\")", "Export"))
                .add("Socket", command.formatted("jakarta.websocket.server.ServerEndpoint(name = \"/ws\")", "Socket"))
                .add("Cart", command.formatted("jakarta.enterprise.context.RequestScoped", "Cart"))
                .add("Archive", command.formatted("Deprecated", "Archive"))
                .guard("shop");

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message).containsExactly(
                "Archive is service-like, but its field 'name' isn't final",
                "Archive is service-like, but its field 'written' isn't final");
        assertThat(guard.types().valueTypesAreImmutable().evaluate()).isEmpty();
    }

    @Test
    void fieldsOnlyTheirSettersSetAreSettingsToTakeInTheConstructor() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Notifier", """
                        package shop;

                        public class Notifier {
                            private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
                            private String webhook = "https://hooks.example.com";
                            private int sent;
                            private String last;

                            public void setWebhook(String webhook) {
                                this.webhook = webhook;
                            }

                            public void setLast(String last) {
                                this.last = last;
                            }

                            public void send(String text) throws Exception {
                                sent++;
                                last = text;
                                http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(webhook)).build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding());
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.types().servicesAreStateless().evaluate()).extracting(Violation::message, Violation::fix)
                .containsExactly(
                        tuple("Notifier is service-like, but its field 'last' isn't final", "keep state out of services:"
                                + " pass it in and return it as values, or keep it in a store the service calls"),
                        tuple("Notifier is service-like, but its field 'sent' isn't final", "keep state out of services:"
                                + " pass it in and return it as values, or keep it in a store the service calls"),
                        tuple("Notifier is service-like, but its field 'webhook' is a setting its setter sets",
                                "take it as a constructor parameter and make the field final"));
    }
}
