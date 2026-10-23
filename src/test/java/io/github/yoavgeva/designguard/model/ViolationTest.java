package io.github.yoavgeva.designguard.model;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ViolationTest {

    @Test
    void rejectsNegativeLine() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Violation("api-break", Violation.Severity.ERROR, "Cart.java", -1, "removed", "keep it"))
                .withMessageContaining("-1");
    }

    @Test
    void requiresAFixHint() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Violation("api-break", Violation.Severity.ERROR, "Cart.java", 3, "removed", null))
                .withMessage("fix");
    }
}
