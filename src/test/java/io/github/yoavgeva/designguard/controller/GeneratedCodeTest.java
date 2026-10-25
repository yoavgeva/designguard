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

    @Test
    void whatMyBatisGeneratorWritesIntoTheSourcesIsGeneratedCode() {
        DesignGuard guard = new CompiledProject(directory)
                .add("Product", """
                        package shop.model;

                        public class Product {
                            private Long id;
                            private String name;

                            public Long getId() {
                                return id;
                            }

                            public void setId(Long id) {
                                this.id = id;
                            }

                            public String getName() {
                                return name;
                            }

                            public void setName(String name) {
                                this.name = name;
                            }
                        }
                        """)
                .add("ProductExample", """
                        package shop.model;

                        public class ProductExample {
                            protected String orderByClause;

                            public void setOrderByClause(String orderByClause) {
                                this.orderByClause = orderByClause;
                            }

                            protected abstract static class GeneratedCriteria {
                                protected boolean valid;
                            }

                            public static class Criteria extends GeneratedCriteria {
                            }
                        }
                        """)
                .add("ProductMapper", """
                        package shop.mapper;

                        import java.util.List;
                        import shop.model.Product;
                        import shop.model.ProductExample;

                        public interface ProductMapper {
                            long countByExample(ProductExample example);

                            int deleteByExample(ProductExample example);

                            int deleteByPrimaryKey(Long id);

                            int insert(Product row);

                            int insertSelective(Product row);

                            List<Product> selectByExample(ProductExample example);

                            Product selectByPrimaryKey(Long id);

                            int updateByExampleSelective(Product row, ProductExample example);

                            int updateByExample(Product row, ProductExample example);

                            int updateByPrimaryKeySelective(Product row);

                            int updateByPrimaryKey(Product row);
                        }
                        """)
                .add("Catalog", "package shop;\n\npublic class Catalog {\n    public String name(shop.mapper.ProductMapper mapper) {\n"
                        + "        return mapper.selectByPrimaryKey(1L).getName();\n    }\n}\n")
                .add("Inventory", "package shop;\n\npublic class Inventory {\n    public long count(shop.mapper.ProductMapper mapper) {\n"
                        + "        return mapper.countByExample(new shop.model.ProductExample());\n    }\n}\n")
                .add("Basket", "package shop;\n\npublic class Basket {\n    private int items;\n\n"
                        + "    public void add() {\n        items++;\n    }\n}\n")
                .guard("shop");

        assertThat(guard.types().interfacesAreFocused().evaluate()).isEmpty();
        assertThat(guard.types().valueTypesAreImmutable().evaluate()).extracting(Violation::message).containsExactly(
                "Basket is a value, but its field 'items' isn't final");
    }
}
