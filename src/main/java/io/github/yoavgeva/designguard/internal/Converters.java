package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Methods that turn outside input into a type, where its raw encoding, as strings or bits, belongs: methods named
 * {@code parse}, {@code from}, {@code valueOf}, {@code of}, {@code decode}, {@code to...}, {@code for...} or
 * {@code convert...}, as {@code toBool}, {@code forExtension} and {@code convertCondition} are, and methods returning
 * one of the project's enums.
 */
final class Converters {

    private static final Pattern CONVERTER = Pattern.compile("(parse|from|valueOf|of|decode|(to|for|convert)(?=[A-Z])).*");

    private final Set<String> enums;

    Converters(JavaSources sources) {
        this.enums = sources.files().stream()
                .flatMap(file -> file.unit().findAll(EnumDeclaration.class).stream())
                .map(EnumDeclaration::getNameAsString)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether the node sits in a converter. */
    boolean contain(Node node) {
        return Scopes.enclosingCallable(node)
                .filter(callable -> CONVERTER.matcher(callable.getNameAsString()).matches()
                        || callable instanceof MethodDeclaration method
                                && enums.contains(method.getType().asString().replaceAll(".*\\.", "")))
                .isPresent();
    }
}
