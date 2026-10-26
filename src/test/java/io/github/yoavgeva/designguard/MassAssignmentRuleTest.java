package io.github.yoavgeva.designguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
class MassAssignmentRuleTest {

    private static final String RUNTIME = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n";
    private static final String WEB = "package org.springframework.web.bind.annotation;\n\n" + RUNTIME;

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void requestsCantSetPrivilegedFields() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("RestController", WEB + "public @interface RestController {\n}\n")
                .add("PutMapping", WEB + "public @interface PutMapping {\n    String[] value() default {};\n}\n")
                .add("PostMapping", WEB + "public @interface PostMapping {\n    String[] value() default {};\n}\n")
                .add("RequestBody", WEB + "public @interface RequestBody {\n}\n")
                .add("PathVariable", WEB + "public @interface PathVariable {\n}\n")
                .add("JsonIgnore", "package com.fasterxml.jackson.annotation;\n\n" + RUNTIME + "public @interface JsonIgnore {\n}\n")
                .add("PreAuthorize", "package org.springframework.security.access.prepost;\n\n" + RUNTIME
                        + "public @interface PreAuthorize {\n    String value();\n}\n")
                .add("UserForm", """
                        package shop;

                        public class UserForm {
                            private String email;
                            private String role;
                            private Long userId;

                            public String getEmail() {
                                return email;
                            }

                            public void setEmail(String email) {
                                this.email = email;
                            }

                            public String getRole() {
                                return role;
                            }

                            public void setRole(String role) {
                                this.role = role;
                            }

                            public Long getUserId() {
                                return userId;
                            }

                            public void setUserId(Long userId) {
                                this.userId = userId;
                            }
                        }
                        """)
                .add("ProfileForm", """
                        package shop;

                        public class ProfileForm {
                            private String name;
                            @com.fasterxml.jackson.annotation.JsonIgnore
                            private boolean admin;

                            public void setName(String name) {
                                this.name = name;
                            }

                            @com.fasterxml.jackson.annotation.JsonIgnore
                            public void setAdmin(boolean admin) {
                                this.admin = admin;
                            }
                        }
                        """)
                .add("OrderRequest", "package shop;\n\npublic record OrderRequest(String item, long ownerId) {\n}\n")
                .add("AccountController", """
                        package shop;

                        import org.springframework.security.access.prepost.PreAuthorize;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.PostMapping;
                        import org.springframework.web.bind.annotation.PutMapping;
                        import org.springframework.web.bind.annotation.RequestBody;
                        import org.springframework.web.bind.annotation.RestController;

                        @RestController
                        public class AccountController {
                            @PutMapping("/account")
                            public String update(@RequestBody UserForm form) {
                                return form.getEmail();
                            }

                            @PutMapping("/profile")
                            public String profile(@RequestBody ProfileForm form) {
                                return form.toString();
                            }

                            @PostMapping("/orders")
                            public String order(OrderRequest request) {
                                return request.item();
                            }

                            @PutMapping("/admin/users/{id}")
                            @PreAuthorize("hasRole('ADMIN')")
                            public String setRole(@PathVariable Long id, @RequestBody UserForm form) {
                                return form.getRole();
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.data().noMassAssignment().evaluate())
                .extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(14, "AccountController.update() binds the request to UserForm, which lets it set role and "
                                + "userId"),
                        tuple(24, "AccountController.order() binds the request to OrderRequest, which lets it set ownerId"));
        assertThat(guard.data().noMassAssignment().evaluate().get(0).fix())
                .isEqualTo("leave role and userId out of UserForm, or mark them @JsonIgnore, and set them from the current "
                        + "user, or in a handler that checks permissions with @PreAuthorize");
    }
}
