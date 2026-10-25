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
class ClassCyclesRuleTest {

    private static final String SHOP = "shop";

    @Parameter
    private int release;

    @TempDir
    private Path directory;

    @Test
    void classesThatDependOnEachOtherFormACycle() {
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("Order", "package shop;\n\npublic class Order {\n    private final java.util.List<OrderLine> lines = java.util.List.of();\n\n"
                        + "    public int size() {\n        return lines.size();\n    }\n}\n")
                .add("OrderLine", "package shop;\n\npublic record OrderLine(Order order, int quantity) {\n}\n")
                .add("Invoice", "package shop.billing;\n\npublic class Invoice {\n"
                        + "    public String pay(Payment payment) {\n        return payment.toString();\n    }\n}\n")
                .add("Payment", "package shop.billing;\n\npublic class Payment {\n"
                        + "    public String receipt(shop.receipts.Receipt receipt) {\n        return receipt.toString();\n    }\n}\n")
                .add("Receipt", "package shop.receipts;\n\npublic class Receipt {\n"
                        + "    public String invoice(shop.billing.Invoice invoice) {\n        return invoice.toString();\n    }\n}\n")
                .add("Shape", "package shop.shapes;\n\npublic sealed interface Shape permits Circle {\n"
                        + "    static Shape unit() {\n        return new Circle(1);\n    }\n}\n")
                .add("Circle", "package shop.shapes;\n\npublic record Circle(double radius) implements Shape {\n}\n")
                .add("Cart", "package shop;\n\npublic class Cart {\n    public record Item(Cart cart) {\n    }\n\n"
                        + "    public Item first() {\n        return new Item(this);\n    }\n}\n")
                .add("Constraint", "package shop.validation;\n\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface Constraint {\n    Class<?>[] validatedBy();\n}\n")
                .add("Xss", "package shop.xss;\n\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "@shop.validation.Constraint(validatedBy = XssValidator.class)\npublic @interface Xss {\n    String message();\n}\n")
                .add("XssValidator", "package shop.xss;\n\npublic class XssValidator {\n"
                        + "    public boolean isValid(String value, Xss annotation) {\n        return annotation.message().isEmpty();\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.architecture().classesHaveNoCycles().evaluate()).extracting(Violation::message).containsExactly(
                "Order and OrderLine depend on each other in a cycle",
                "Invoice, Payment and Receipt depend on each other in a cycle");
        assertThat(guard.architecture().classesHaveNoCycles().evaluate().get(0).fix()).isEqualTo("break the cycle:"
                + " move what they share into a class of its own, or let one side depend on an interface it owns");
    }

    @Test
    void theInverseSideOfAnEntityAssociationIsNoCycle() {
        String association = "package jakarta.persistence;\n\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                + "public @interface %s {\n    String mappedBy() default \"\";\n}\n";
        DesignGuard guard = new CompiledProject(directory).release(release)
                .add("OneToMany", association.formatted("OneToMany"))
                .add("ManyToMany", association.formatted("ManyToMany"))
                .add("ManyToOne", "package jakarta.persistence;\n\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface ManyToOne {\n}\n")
                .add("Owner", """
                        package shop.clinic;

                        import jakarta.persistence.OneToMany;
                        import java.util.ArrayList;
                        import java.util.List;

                        public class Owner {
                            @OneToMany(mappedBy = "owner")
                            private final List<Pet> pets = new ArrayList<>();

                            public void addPet(Pet pet) {
                                pets.add(pet);
                                pet.setOwner(this);
                            }

                            public Pet firstPet() {
                                return pets.get(0);
                            }
                        }
                        """)
                .add("Pet", """
                        package shop.clinic;

                        import jakarta.persistence.ManyToOne;
                        import jakarta.persistence.OneToMany;
                        import java.util.List;

                        public class Pet {
                            @ManyToOne
                            private Owner owner;

                            @OneToMany(mappedBy = "pet")
                            private List<Visit> visits;

                            void setOwner(Owner owner) {
                                this.owner = owner;
                            }

                            public int visitCount() {
                                return visits.size() + (owner == null ? 0 : 1);
                            }
                        }
                        """)
                .add("Visit", "package shop.clinic;\n\npublic class Visit {\n    @jakarta.persistence.ManyToOne\n    private Pet pet;\n\n"
                        + "    public Pet pet() {\n        return pet;\n    }\n}\n")
                .add("Post", "package shop.blog;\n\npublic class Post {\n    @jakarta.persistence.ManyToMany\n"
                        + "    private java.util.Set<Tag> tags;\n\n    public int tagCount() {\n        return tags.size();\n    }\n}\n")
                .add("Tag", "package shop.blog;\n\npublic class Tag {\n    @jakarta.persistence.ManyToMany\n"
                        + "    private java.util.Set<Post> posts;\n\n    public int postCount() {\n        return posts.size();\n    }\n}\n")
                .guard(SHOP);

        assertThat(guard.architecture().classesHaveNoCycles().evaluate()).extracting(Violation::message)
                .containsExactly("Post and Tag depend on each other in a cycle");
    }
}
