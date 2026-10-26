package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The project classes a test uses for real: those it names in a type or as a call's scope, and doesn't mock. */
final class UsedClasses {

    private UsedClasses() {
    }

    /** The project classes the test names in a type or as a call's scope, other than the subject and mocked types. */
    static Set<JavaClass> named(ParsedFile file, TypeDeclaration<?> test, JavaClass subject, SourceTypes types) {
        Set<String> mocked = MockSites.in(file.unit()).filter(mock -> !mock.spy()).map(MockSites.MockSite::typeName)
                .collect(Collectors.toSet());
        Stream<String> typeNames = test.findAll(ClassOrInterfaceType.class).stream().map(ClassOrInterfaceType::getNameWithScope);
        Stream<String> scopes = test.findAll(MethodCallExpr.class).stream()
                .flatMap(call -> call.getScope().filter(Expression::isNameExpr).stream())
                .map(scope -> scope.asNameExpr().getNameAsString());
        return Stream.concat(typeNames, scopes)
                .filter(name -> !mocked.contains(name.substring(name.lastIndexOf('.') + 1)))
                .flatMap(name -> types.resolve(file.unit(), name).stream())
                .filter(type -> !type.equals(subject))
                .collect(Collectors.toSet());
    }
}
