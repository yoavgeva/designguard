package io.github.yoavgeva.designguard.model;

import java.lang.annotation.Annotation;
import java.util.Objects;

/**
 * An annotation identified by name, matched against annotations as written in source.
 *
 * @param simpleName    e.g. {@code Flaky}
 * @param qualifiedName e.g. {@code com.shop.testing.Flaky}
 */
public record AnnotationName(String simpleName, String qualifiedName) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if any component is null
     */
    public AnnotationName {
        Objects.requireNonNull(simpleName, "simpleName");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
    }

    /**
     * Names an annotation, for a rule's {@code ignoring(...)}.
     *
     * @param annotation the annotation type
     * @return its name, matched simply or qualified against the code
     */
    public static AnnotationName annotatedWith(Class<? extends Annotation> annotation) {
        return new AnnotationName(annotation.getSimpleName(), annotation.getCanonicalName());
    }

    /**
     * Whether an annotation written in source refers to this one.
     *
     * @param writtenName the name as written after {@code @}, simple or qualified
     * @return true if it is the simple or the qualified name
     */
    public boolean matches(String writtenName) {
        return simpleName.equals(writtenName) || qualifiedName.equals(writtenName);
    }
}
