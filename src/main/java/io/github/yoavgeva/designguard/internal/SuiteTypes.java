package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The top-level types of the test sources, and the ones each of them names or extends. */
final class SuiteTypes {

    private final List<TestType> types;
    private final Map<String, TestType> byName;

    SuiteTypes(JavaSources testSources) {
        this.types = testSources.files().stream()
                .flatMap(file -> file.unit().getTypes().stream().map(type -> new TestType(file, type)))
                .toList();
        this.byName = types.stream()
                .collect(Collectors.toUnmodifiableMap(test -> test.type().getNameAsString(), test -> test,
                        (first, second) -> first));
    }

    /** Every top-level type of the test sources. */
    List<TestType> all() {
        return types;
    }

    /** The other test-source classes the test names, such as a shared fixture. */
    Stream<TestType> helpersOf(TestType test) {
        Stream<String> typeNames = test.type().findAll(ClassOrInterfaceType.class).stream().map(ClassOrInterfaceType::getNameAsString);
        Stream<String> scopes = test.type().findAll(NameExpr.class).stream().map(NameExpr::getNameAsString);
        return Stream.concat(typeNames, scopes)
                .distinct()
                .filter(name -> !name.equals(test.type().getNameAsString()))
                .flatMap(name -> Optional.ofNullable(byName.get(name)).stream());
    }

    /**
     * The test classes the test extends, whose tests it runs, as an integration test extending the unit test does. A base
     * class that declares no tests, only setup and helpers, isn't one.
     */
    Stream<TestType> testSuperclassesOf(TestType test) {
        return test.type() instanceof ClassOrInterfaceDeclaration declaration
                ? declaration.getExtendedTypes().stream()
                        .flatMap(extended -> Optional.ofNullable(byName.get(extended.getNameAsString())).stream())
                        .filter(parent -> parent.type().getMethods().stream().anyMatch(UnitTestMethods::isTest))
                : Stream.empty();
    }

    /** A top-level type in the test sources, and its file. */
    record TestType(ParsedFile file, TypeDeclaration<?> type) {
    }
}
