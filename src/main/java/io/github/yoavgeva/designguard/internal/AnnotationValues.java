package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Reads annotation attributes written in source. */
final class AnnotationValues {

    private AnnotationValues() {
    }

    /** The expression of an attribute: {@code value} also covers the single-member form {@code @A("x")}. */
    static Optional<Expression> attribute(AnnotationExpr annotation, String name) {
        if (annotation instanceof SingleMemberAnnotationExpr single && "value".equals(name)) {
            return Optional.of(single.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals(name))
                    .map(MemberValuePair::getValue)
                    .findFirst();
        }
        return Optional.empty();
    }

    /** The string literals of an attribute, whether written as one string or as an array of strings. */
    static List<String> strings(AnnotationExpr annotation, String name) {
        return attribute(annotation, name).stream()
                .flatMap(value -> value instanceof ArrayInitializerExpr array ? array.getValues().stream() : Stream.of(value))
                .filter(StringLiteralExpr.class::isInstance)
                .map(value -> ((StringLiteralExpr) value).getValue())
                .toList();
    }
}
