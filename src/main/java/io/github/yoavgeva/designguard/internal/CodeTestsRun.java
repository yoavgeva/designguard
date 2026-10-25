package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The test sources' code that tests run: the types that declare tests, and the test-source types those name, as a
 * base class, a fixture or a sample builder are, and so on until none is added. Other code in the test sources, such
 * as a Gatling simulation, runs in no test.
 */
final class CodeTestsRun {

    private final Set<TypeDeclaration<?>> run;

    private CodeTestsRun(Set<TypeDeclaration<?>> run) {
        this.run = run;
    }

    /** The code the tests among the sources run, by top-level type. */
    static CodeTestsRun in(JavaSources tests) {
        Map<String, TypeDeclaration<?>> topLevelByName = tests.files().stream()
                .flatMap(file -> file.unit().getTypes().stream())
                .flatMap(top -> top.findAll(TypeDeclaration.class).stream()
                        .map(type -> Map.entry(type.getNameAsString(), top)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
        Set<TypeDeclaration<?>> run = new HashSet<>();
        Deque<TypeDeclaration<?>> pending = new ArrayDeque<>();
        tests.files().forEach(file -> file.unit().getTypes().stream()
                .filter(top -> top.findAll(MethodDeclaration.class).stream().anyMatch(UnitTestMethods::isTest))
                .forEach(pending::add));
        while (!pending.isEmpty()) {
            TypeDeclaration<?> type = pending.pop();
            if (run.add(type)) {
                namesIn(type).flatMap(name -> Stream.ofNullable(topLevelByName.get(name))).forEach(pending::add);
            }
        }
        return new CodeTestsRun(run);
    }

    /** Whether a test runs the type, or the type it is nested in. */
    boolean isRun(TypeDeclaration<?> topLevel) {
        return run.contains(topLevel);
    }

    private static Stream<String> namesIn(TypeDeclaration<?> type) {
        Stream<String> imported = type.findCompilationUnit().stream()
                .flatMap(unit -> unit.getImports().stream())
                .map(ImportDeclaration::getName)
                .flatMap(name -> Stream.concat(Stream.of(name.getIdentifier()),
                        name.getQualifier().map(Name::getIdentifier).stream()));
        return Stream.of(type.findAll(ClassOrInterfaceType.class).stream().map(ClassOrInterfaceType::getNameAsString),
                        type.findAll(NameExpr.class).stream().map(NameExpr::getNameAsString), imported)
                .flatMap(names -> names);
    }
}
