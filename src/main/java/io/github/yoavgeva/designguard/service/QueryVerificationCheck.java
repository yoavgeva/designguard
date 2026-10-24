package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.Type;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds tests that verify queries: {@code verify(mock).method(...)} where every such method of the mock's type
 * returns a value. Stub queries and assert on the result; verify only commands: methods that return nothing, or only a
 * {@code Void} publisher or future such as {@code Mono<Void>}, and methods named for a change, as {@code update} or
 * {@code sendCode} are, which may return what they stored, as reactive clients and repositories do. A verification
 * with a mode, such as {@code times(1)} or {@code never()}, counts calls on purpose and is fine.
 */
public final class QueryVerificationCheck {

    private static final String RULE = "tests-check-results";
    private static final String VERIFY = "verify";

    /**
     * Finds violations in the test sources the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        SourceTypes types = new SourceTypes(code.projectClasses());
        return code.testSources().files().stream()
                .flatMap(file -> file.unit().findAll(MethodCallExpr.class).stream()
                        .filter(call -> Scopes.selected(filter, file, call))
                        .flatMap(call -> verifiedMock(call).stream()
                                .flatMap(mock -> mockType(file.unit(), mock, types).stream())
                                .filter(type -> isQuery(type, call))
                                .map(type -> violation(file, call, type))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /** The mock in {@code verify(mock).method(...)}, a plain verification without a mode. */
    private static Optional<NameExpr> verifiedMock(MethodCallExpr call) {
        return call.getScope()
                .flatMap(Expression::toMethodCallExpr)
                .filter(verify -> VERIFY.equals(verify.getNameAsString()) && verify.getArguments().size() == 1)
                .flatMap(verify -> verify.getArgument(0).toNameExpr());
    }

    /** The project class the mock variable is declared as: a local, a parameter or a field. */
    private static Optional<JavaClass> mockType(CompilationUnit unit, NameExpr mock, SourceTypes types) {
        String name = mock.getNameAsString();
        Optional<CallableDeclaration<?>> callable = Scopes.enclosingCallable(mock);
        Stream<VariableDeclarator> locals = callable.stream()
                .flatMap(method -> method.findAll(VariableDeclarator.class).stream());
        Stream<VariableDeclarator> fields = Scopes.enclosingType(mock).stream()
                .flatMap(type -> type.getFields().stream())
                .flatMap(field -> field.getVariables().stream());
        Optional<String> declared = Stream.concat(locals, fields)
                .filter(variable -> variable.getNameAsString().equals(name))
                .findFirst()
                .flatMap(QueryVerificationCheck::declaredType)
                .or(() -> callable.flatMap(method -> method.getParameterByName(name))
                        .map(Parameter::getType)
                        .flatMap(SourceTypes::named));
        return declared.flatMap(written -> types.resolve(unit, written));
    }

    /** The variable's type as written, or, for {@code var}, the type its {@code mock(Type.class)} creates. */
    private static Optional<String> declaredType(VariableDeclarator variable) {
        Type type = variable.getType();
        if (!type.isVarType()) {
            return SourceTypes.named(type);
        }
        return variable.getInitializer()
                .flatMap(Expression::toMethodCallExpr)
                .filter(call -> !call.getArguments().isEmpty())
                .flatMap(call -> call.getArgument(0).toClassExpr())
                .map(ClassExpr::getType)
                .flatMap(SourceTypes::named);
    }

    /** Whether every method of the type with the verified name and argument count returns a value. */
    private static boolean isQuery(JavaClass type, MethodCallExpr call) {
        List<JavaMethod> candidates = type.getAllMethods().stream()
                .filter(method -> method.getName().equals(call.getNameAsString())
                        && method.getRawParameterTypes().size() == call.getArguments().size())
                .toList();
        return !candidates.isEmpty() && candidates.stream().noneMatch(CommandMethods::isCommand);
    }

    private static Violation violation(ParsedFile file, MethodCallExpr call, JavaClass type) {
        String test = Scopes.enclosingCallable(call).map(Scopes::describe).orElse("a test");
        return Scopes.violation(RULE, file, call, "test " + test + " verifies " + ProjectClasses.nameOf(type) + "."
                        + call.getNameAsString() + "(), a query that returns a value",
                "stub it with when(...).thenReturn(...) and assert on what the code under test returns; verify only"
                        + " commands");
    }
}
