package io.github.yoavgeva.designguard.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.DesignGuardException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceReaderTest {

    @TempDir
    private Path root;

    @Test
    void parsesEveryJavaFileOrderedByPath() {
        new SourceTree(root)
                .add("Order", "package shop.orders;\n\npublic class Order {\n}\n")
                .add("Cart", "package shop;\n\npublic class Cart {\n}\n");

        assertThat(SourceReader.read(List.of(root)).files()).extracting(ParsedFile::packageName)
                .containsExactly("shop", "shop.orders");
    }

    @Test
    void failsWithTheFileNameWhenSourceDoesNotParse() {
        new SourceTree(root).add("Cart", "package shop;\n\nclass Cart {\n");

        assertThatThrownBy(() -> SourceReader.read(List.of(root)))
                .isInstanceOf(DesignGuardException.class)
                .hasMessageContaining("Cart.java");
    }

    @Test
    void failsWhenASourceRootIsMissing() {
        Path missing = root.resolve("missing");

        assertThatThrownBy(() -> SourceReader.read(List.of(missing)))
                .isInstanceOf(DesignGuardException.class)
                .hasMessageContaining("does not exist")
                .hasMessageContaining("withSourceRoots");
    }
}
