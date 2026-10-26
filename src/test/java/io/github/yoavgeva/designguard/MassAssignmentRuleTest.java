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
                .add("Order", "package shop;\n\npublic class Order {\n    private long ownerId;\n\n"
                        + "    public void setOwnerId(long ownerId) {\n        this.ownerId = ownerId;\n    }\n}\n")
                .add("Repository", "package org.springframework.data.repository;\n\npublic interface Repository<T, ID> {\n}\n")
                .add("CrudRepository", "package org.springframework.data.repository;\n\n"
                        + "public interface CrudRepository<T, ID> extends Repository<T, ID> {\n    <S extends T> S save(S entity);\n}\n")
                .add("BeanUtils", "package org.springframework.beans;\n\npublic final class BeanUtils {\n"
                        + "    private BeanUtils() {\n    }\n\n    public static void copyProperties(Object source, Object target) {\n"
                        + "    }\n}\n")
                .add("MailConfig", "package shop;\n\npublic class MailConfig {\n    private String user;\n\n"
                        + "    public void setUser(String user) {\n        this.user = user;\n    }\n}\n")
                .add("Member", "package shop;\n\npublic class Member {\n}\n")
                .add("Comment", "package shop;\n\npublic class Comment {\n    private String text;\n    private Member user;\n\n"
                        + "    public void setText(String text) {\n        this.text = text;\n    }\n\n"
                        + "    public void setUser(Member user) {\n        this.user = user;\n    }\n}\n")
                .add("Forms", "package shop;\n\npublic interface Forms extends org.springframework.data.repository.CrudRepository<Object, Long> {\n}\n")
                .add("Accounts", """
                        package shop;

                        public class Accounts {
                            private final Forms forms;

                            public Accounts(Forms forms) {
                                this.forms = forms;
                            }

                            public Object update(UserForm form) {
                                return forms.save(form);
                            }

                            public Object updateEmail(UserForm form) {
                                UserForm copy = new UserForm();
                                copy.setEmail(form.getEmail());
                                return forms.save(copy);
                            }
                        }
                        """)
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
                            private final Accounts accounts;
                            private final Forms forms;

                            public AccountController(Accounts accounts, Forms forms) {
                                this.accounts = accounts;
                                this.forms = forms;
                            }

                            @PutMapping("/account")
                            public Object update(@RequestBody UserForm form) {
                                return accounts.update(form);
                            }

                            @PutMapping("/account/email")
                            public Object email(@RequestBody UserForm form) {
                                return accounts.updateEmail(form);
                            }

                            @PutMapping("/account/mine")
                            public Object mine(@RequestBody UserForm form) {
                                form.setRole("USER");
                                form.setUserId(1L);
                                return accounts.update(form);
                            }

                            @PutMapping("/profile")
                            public Object profile(@RequestBody ProfileForm form) {
                                return forms.save(form);
                            }

                            @PostMapping("/orders")
                            public Object order(OrderRequest request) {
                                Order order = new Order();
                                org.springframework.beans.BeanUtils.copyProperties(request, order);
                                return forms.save(order);
                            }

                            @PutMapping("/admin/users/{id}")
                            @PreAuthorize("hasRole('ADMIN')")
                            public Object setRole(@PathVariable Long id, @RequestBody UserForm form) {
                                return accounts.update(form);
                            }

                            @PostMapping("/comments")
                            public Object comment(@RequestBody Comment comment) {
                                return forms.save(comment);
                            }

                            @PutMapping("/mail")
                            public Object mail(@RequestBody MailConfig config) {
                                return forms.save(config);
                            }

                            @PutMapping("/account/preview")
                            public String preview(@RequestBody UserForm form) {
                                return form.getRole();
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.data().noMassAssignment().evaluate())
                .extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(22, "AccountController.update() saves the request's UserForm through Accounts.update(), "
                                + "which calls Forms.save(), so the request can set role and userId"),
                        tuple(45, "AccountController.order() saves the request's OrderRequest through "
                                + "BeanUtils.copyProperties(), so the request can set ownerId"),
                        tuple(57, "AccountController.comment() saves the request's Comment through Forms.save(), so the "
                                + "request can set user"));
        assertThat(guard.data().noMassAssignment().evaluate().get(0).fix())
                .isEqualTo("leave role and userId out of UserForm, or mark them @JsonIgnore, and set them from the current "
                        + "user, or in a handler that checks permissions with @PreAuthorize");
    }
}
