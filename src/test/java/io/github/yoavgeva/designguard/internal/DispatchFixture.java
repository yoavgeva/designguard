package io.github.yoavgeva.designguard.internal;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.TypeDispatchPolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;

/** The types the type-dispatch tests branch on: a sealed hierarchy, an open one, and an enum. */
final class DispatchFixture {

    /** The first Java version with every feature the check may suggest. */
    private static final int JAVA_21 = 21;

    private DispatchFixture() {
    }

    /**
     * Writes {@code Shape} (sealed: {@code Circle}, {@code Square}), {@code Animal} (open: {@code Cat}, {@code Dog})
     * and the enum {@code OrderType}, all in package {@code shop}.
     *
     * @param root the source root
     * @return the tree, to add the code under test
     */
    static SourceTree writeTypes(Path root) {
        return new SourceTree(root)
                .add("Shape", "package shop;\n\npublic sealed interface Shape permits Circle, Square {\n}\n")
                .add("Circle", "package shop;\n\npublic record Circle(double r) implements Shape {\n}\n")
                .add("Square", "package shop;\n\npublic record Square(double side) implements Shape {\n}\n")
                .add("Animal", "package shop;\n\npublic interface Animal {\n}\n")
                .add("Cat", "package shop;\n\npublic record Cat() implements Animal {\n}\n")
                .add("Dog", "package shop;\n\npublic record Dog() implements Animal {\n}\n")
                .add("OrderType", "package shop;\n\npublic enum OrderType {\n    EXPRESS, PICKUP, STANDARD\n}\n");
    }

    /**
     * Runs the check over everything under the root.
     *
     * @param root             the source root
     * @param policy           the policy to check with
     * @param maxPlacesPerType the per-type limit
     * @return the violations
     */
    static List<Violation> violations(Path root, TypeDispatchPolicy policy, int maxPlacesPerType) {
        return new TypeDispatchCheck(policy, maxPlacesPerType, JAVA_21)
                .check(SourceReader.read(List.of(root)), ClassFilter.ALL);
    }
}
