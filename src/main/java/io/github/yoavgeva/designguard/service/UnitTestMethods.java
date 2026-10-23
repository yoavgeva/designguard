package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.util.Optional;
import java.util.Set;

/** Recognises test methods of JUnit 4, JUnit 5, TestNG and jqwik by their annotations. */
final class UnitTestMethods {

    private static final Set<String> TEST_ANNOTATIONS =
            Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate", "Property");

    private UnitTestMethods() {
    }

    static boolean isTest(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(annotation -> TEST_ANNOTATIONS.contains(simpleName(annotation)));
    }

    /** The annotation with the given simple name, written simply or qualified. */
    static Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> member, String simpleName) {
        return member.getAnnotations().stream().filter(annotation -> simpleName.equals(simpleName(annotation))).findFirst();
    }

    static String simpleName(AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
