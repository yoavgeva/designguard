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
class UnboundedQueriesRuleTest {

    private static final String RUNTIME = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n";
    private static final String WEB = "package org.springframework.web.bind.annotation;\n\n" + RUNTIME;
    private static final String DOMAIN = "package org.springframework.data.domain;\n\n";
    private static final String GROWS = ", so each request loads more as the table grows";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void handlersDontLoadWholeTables() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("RestController", WEB + "public @interface RestController {\n}\n")
                .add("GetMapping", WEB + "public @interface GetMapping {\n    String[] value() default {};\n}\n")
                .add("Sort", DOMAIN + "public final class Sort {\n}\n")
                .add("Pageable", DOMAIN + "public interface Pageable {\n}\n")
                .add("Page", DOMAIN + "public interface Page<T> {\n}\n")
                .add("Repository", "package org.springframework.data.repository;\n\npublic interface Repository<T, ID> {\n}\n")
                .add("CrudRepository", "package org.springframework.data.repository;\n\n"
                        + "public interface CrudRepository<T, ID> extends Repository<T, ID> {\n"
                        + "    Iterable<T> findAll();\n\n    java.util.Optional<T> findById(ID id);\n}\n")
                .add("PagingAndSortingRepository", "package org.springframework.data.repository;\n\n"
                        + "public interface PagingAndSortingRepository<T, ID> extends Repository<T, ID> {\n"
                        + "    Iterable<T> findAll(org.springframework.data.domain.Sort sort);\n\n"
                        + "    org.springframework.data.domain.Page<T> findAll(org.springframework.data.domain.Pageable pageable);\n}\n")
                .add("IService", "package com.baomidou.mybatisplus.extension.service;\n\n"
                        + "public interface IService<T> {\n    java.util.List<T> list();\n}\n")
                .add("ProductRepository", "package shop;\n\npublic interface ProductRepository extends "
                        + "org.springframework.data.repository.CrudRepository<String, Long>, "
                        + "org.springframework.data.repository.PagingAndSortingRepository<String, Long> {\n"
                        + "    java.util.List<String> findByCategory(String category);\n}\n")
                .add("TagService", "package shop;\n\npublic interface TagService extends "
                        + "com.baomidou.mybatisplus.extension.service.IService<String> {\n}\n")
                .add("Catalog", """
                        package shop;

                        public class Catalog {
                            private final ProductRepository products;

                            public Catalog(ProductRepository products) {
                                this.products = products;
                            }

                            public Iterable<String> all() {
                                return products.findAll();
                            }

                            public Iterable<String> byName() {
                                return products.findAll(new org.springframework.data.domain.Sort());
                            }

                            public Object page(org.springframework.data.domain.Pageable pageable) {
                                return products.findAll(pageable);
                            }

                            public java.util.List<String> inCategory(String category) {
                                return products.findByCategory(category);
                            }
                        }
                        """)
                .add("ProductController", """
                        package shop;

                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;

                        @RestController
                        public class ProductController {
                            private final Catalog catalog;
                            private final TagService tags;

                            public ProductController(Catalog catalog, TagService tags) {
                                this.catalog = catalog;
                                this.tags = tags;
                            }

                            @GetMapping("/products")
                            public Iterable<String> list() {
                                return catalog.all();
                            }

                            @GetMapping("/products/by-name")
                            public Iterable<String> byName() {
                                return catalog.byName();
                            }

                            @GetMapping("/products/page")
                            public Object page(org.springframework.data.domain.Pageable pageable) {
                                return catalog.page(pageable);
                            }

                            @GetMapping("/products/category")
                            public java.util.List<String> category(String category) {
                                return catalog.inCategory(category);
                            }

                            @GetMapping("/tags")
                            public java.util.List<String> tags() {
                                return tags.list();
                            }

                            public Iterable<String> notAHandler() {
                                return catalog.all();
                            }
                        }
                        """)
                .guard("shop");

        assertThat(guard.data().noUnboundedQueries().evaluate())
                .extracting(Violation::line, Violation::message)
                .containsExactly(
                        tuple(18, "ProductController.list() loads every row through "
                                + "Catalog.all(), which calls ProductRepository.findAll()" + GROWS),
                        tuple(23, "ProductController.byName() loads every row through "
                                + "Catalog.byName(), which calls ProductRepository.findAll(Sort)" + GROWS),
                        tuple(38, "ProductController.tags() loads every row through "
                                + "TagService.list()" + GROWS));
        assertThat(guard.data().noUnboundedQueries().evaluate().get(0).fix())
                .isEqualTo("take a Pageable and query a page, or filter the query to what the response shows");
    }
}
