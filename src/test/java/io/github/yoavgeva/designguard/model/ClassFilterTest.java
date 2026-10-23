package io.github.yoavgeva.designguard.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClassFilterTest {

    private static final String BILLING = "com.shop.billing";

    @Test
    void selectsEverythingByDefault() {
        assertThat(ClassFilter.ALL.selects(BILLING, List.of())).isTrue();
    }

    @Test
    void includedPackagesUseArchUnitSyntax() {
        var filter = ClassFilter.ALL.including("..billing..", "..orders..");

        assertThat(filter.selects(BILLING, List.of())).isTrue();
        assertThat(filter.selects("com.shop.orders.api", List.of())).isTrue();
        assertThat(filter.selects("com.shop.users", List.of())).isFalse();
    }

    @Test
    void exclusionWinsOverInclusion() {
        var filter = ClassFilter.ALL.including("com.shop..").excluding("..legacy..");

        assertThat(filter.selects("com.shop.legacy.billing", List.of())).isFalse();
        assertThat(filter.selects(BILLING, List.of())).isTrue();
    }

    @Test
    void ignoredAnnotationsMatchSimpleOrQualifiedNames() {
        var filter = ClassFilter.ALL.ignoring(new AnnotationName("Generated", "com.shop.Generated"));

        assertThat(filter.selects(BILLING, List.of("Generated"))).isFalse();
        assertThat(filter.selects(BILLING, List.of("com.shop.Generated"))).isFalse();
        assertThat(filter.selects(BILLING, List.of("Override"))).isTrue();
    }
}
