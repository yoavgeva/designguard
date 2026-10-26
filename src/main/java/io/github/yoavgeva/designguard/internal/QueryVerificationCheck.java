package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
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
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds tests that verify queries: {@code verify(mock).method(...)} where every such method of the mock's type
 * returns a value. Stub queries and assert on the result; verify only commands: methods that return nothing, or only a
 * {@code Void} publisher or future such as {@code Mono<Void>}, and methods named for a change, as {@code update} or
 * {@code sendCode} are, which may return what they stored, as reactive clients and repositories do. A verification
 * with a mode, such as {@code times(1)} or {@code never()}, counts calls on purpose and is fine. Verifying a query the
 * test stubs only repeats the stub, as Mockito's documentation says, and is reported as such.
 */
public final class QueryVerificationCheck {

    private static final String RULE = "tests-check-results";
    private static final String VERIFY = "verify";
    /** Mockito's and BDDMockito's stubbing calls: {@code when(...)} and {@code given(...)}. */
    private static final Set<String> STUBS = Set.of("when", "given");

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
                                .flatMap(mock -> mockType(file.unit(), mock, types).stream()
                                        .filter(type -> isQuery(type, call))
                                        .map(type -> violation(file, call, type, isStubbedThere(call, mock))))))
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

    /**
     * Whether the test stubs the verified method of the mock, as {@code when(rates.rateOf("EUR"))} and
     * {@code doReturn(2L).when(rates).rateOf("EUR")} do: it controls the result, so a verification only repeats the stub.
     */
    private static boolean isStubbedThere(MethodCallExpr verified, NameExpr mock) {
        String method = verified.getNameAsString();
        return Scopes.enclosingCallable(verified).stream()
                .flatMap(callable -> callable.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> STUBS.contains(call.getNameAsString()) && call.getArguments().size() == 1
                        && (isCallOn(call.getArgument(0), mock, method) || isName(call.getArgument(0), mock)
                                && call.getParentNode().filter(parent -> isCallOn(parent, mock, method)).isPresent()));
    }

    /** Whether the node calls the method on the mock, or on {@code when(mock)}, as a stub's last step does. */
    private static boolean isCallOn(Node node, NameExpr mock, String method) {
        return node instanceof MethodCallExpr call && call.getNameAsString().equals(method)
                && call.getScope().filter(scope -> isName(scope, mock) || scope instanceof MethodCallExpr when
                        && STUBS.contains(when.getNameAsString())).isPresent();
    }

    private static boolean isName(Node node, NameExpr mock) {
        return node instanceof NameExpr name && name.getNameAsString().equals(mock.getNameAsString());
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

    /** The violation; a query the test stubs is one it already controls, so the verification only repeats the stub. */
    private static Violation violation(ParsedFile file, MethodCallExpr call, JavaClass type, boolean stubbed) {
        String test = Scopes.enclosingCallable(call).map(Scopes::describe).orElse("a test");
        String verified = "test " + test + " verifies " + ProjectClasses.nameOf(type) + "." + call.getNameAsString() + "()";
        if (stubbed) {
            return Scopes.violation(RULE, file, call, verified + ", a query it stubs, so the verification only repeats"
                            + " the stub", "drop the verification, and assert on what the code under test returns");
        }
        return Scopes.violation(RULE, file, call, verified + ", a query that returns a value",
                "stub it with when(...).thenReturn(...) and assert on what the code under test returns; verify only"
                        + " commands");
    }
}
