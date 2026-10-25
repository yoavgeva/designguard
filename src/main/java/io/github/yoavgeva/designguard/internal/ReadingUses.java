package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What a test's code does with a value, for {@link ReportedReadings}: the calls that print or log it, compute from it
 * or store it, and the variables, parameters and methods of the test class it flows through.
 */
final class ReadingUses {

    private static final Set<String> PRINTS = Set.of("print", "println", "printf", "format");
    private static final Set<String> CONSOLE = Set.of("System.out", "System.err");
    private static final Set<String> LOGS = Set.of("trace", "debug", "info", "warn", "warning", "error", "fine", "finer",
            "finest", "severe", "config", "log");
    /** Classes whose static methods compute values, as {@code Math.max} and {@code Arrays.sort} do. */
    private static final Set<String> CALCULATORS = Set.of("Math", "StrictMath", "Arrays", "Long", "Integer", "Double",
            "Float", "String", "Duration");
    /** Methods that convert or format a value on any receiver, as {@code TimeUnit.NANOSECONDS.toMillis(...)} does. */
    private static final Set<String> CONVERSIONS = Set.of("toNanos", "toMicros", "toMillis", "toSeconds", "toMinutes",
            "toHours", "toDays", "convert", "ofNanos", "ofMillis", "ofSeconds", "between", "formatted");
    /** Methods of collections and atomics that store a value in them. */
    private static final Set<String> STORES = Set.of("add", "addAll", "put", "set", "offer", "push", "addFirst",
            "addLast", "addAndGet", "getAndAdd", "accumulateAndGet", "getAndAccumulate", "lazySet", "getAndSet");
    /** What a collection, array or string tells of its size, which the values in it don't decide. */
    private static final Set<String> SIZES = Set.of("size", "isEmpty", "length");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]*");

    private ReadingUses() {
    }

    /** Whether the call prints to the console or logs, as {@code System.out.printf(...)} and {@code LOG.info(...)} do. */
    static boolean isReport(MethodCallExpr call) {
        String name = call.getNameAsString();
        return call.getScope().filter(scope -> PRINTS.contains(name) && CONSOLE.contains(scope.toString())
                        || LOGS.contains(name) && scope instanceof NameExpr logger
                                && logger.getNameAsString().toLowerCase(Locale.ROOT).contains("log"))
                .isPresent();
    }

    /** Whether the call computes a value from its arguments, as {@code Math.max(...)} and {@code toMillis(...)} do. */
    static boolean isCalculation(MethodCallExpr call) {
        return CONVERSIONS.contains(call.getNameAsString()) || call.getScope()
                .filter(scope -> scope instanceof NameExpr type && CALCULATORS.contains(type.getNameAsString()))
                .isPresent();
    }

    /** Whether the call stores its arguments in the object it is called on, as {@code times.add(time)} does. */
    static boolean isStore(MethodCallExpr call) {
        return STORES.contains(call.getNameAsString());
    }

    /** Whether the field's name is a constant's, as {@code Calendar.DECEMBER} is, which no call fills. */
    static boolean isConstant(String name) {
        return CONSTANT.matcher(name).matches();
    }

    /** Whether the method or field, as {@code size()} and {@code length} are, tells only how many values there are. */
    static boolean isSize(String name) {
        return SIZES.contains(name);
    }

    /**
     * The methods with a body of the call's class that a call with no receiver, or on {@code this}, may run: those of
     * its name taking as many arguments. None if any of them has no body, or the call has another receiver.
     */
    static List<MethodDeclaration> ownMethods(MethodCallExpr call) {
        boolean onThis = call.getScope().map(Expression::isThisExpr).orElse(true);
        List<MethodDeclaration> methods = onThis ? Scopes.nearest(call, ClassOrInterfaceDeclaration.class).stream()
                .flatMap(type -> type.getMethodsByName(call.getNameAsString()).stream())
                .filter(method -> method.getParameters().size() == call.getArguments().size())
                .toList() : List.of();
        return methods.stream().allMatch(method -> method.getBody().isPresent()) ? methods : List.of();
    }

    /** The local variable or parameter the name reads, declared in the method or constructor around it. */
    static Optional<Node> declarationOf(NameExpr name) {
        return Scopes.enclosingCallable(name).flatMap(callable -> Stream.<Node>concat(
                        callable.findAll(VariableDeclarator.class).stream(), callable.findAll(Parameter.class).stream())
                .filter(declared -> declared instanceof NodeWithSimpleName<?> named
                        && named.getNameAsString().equals(name.getNameAsString()))
                .findFirst());
    }

    /** Whether the declaration is of a local variable the code creates an array, collection or other object for. */
    static boolean isCreatedThere(Node declaration) {
        return declaration instanceof VariableDeclarator variable && variable.getInitializer()
                .filter(value -> value.isObjectCreationExpr() || value.isArrayCreationExpr() || value.isArrayInitializerExpr())
                .isPresent();
    }

    /** The expressions that read the variable or parameter, in the method or constructor that declares it. */
    static List<NameExpr> usesOf(Node declaration) {
        String name = declaration instanceof NodeWithSimpleName<?> named ? named.getNameAsString() : "";
        return Scopes.enclosingCallable(declaration).map(callable -> callable.findAll(NameExpr.class).stream()
                        .filter(use -> use.getNameAsString().equals(name))
                        .toList())
                .orElse(List.of());
    }

    /** The private method the statement returns from; none for a lambda's return or another method's. */
    static Optional<MethodDeclaration> returningMethod(ReturnStmt returned) {
        Optional<LambdaExpr> lambda = Scopes.nearest(returned, LambdaExpr.class);
        Optional<CallableDeclaration<?>> callable = Scopes.enclosingCallable(returned);
        return callable.filter(MethodDeclaration.class::isInstance).map(MethodDeclaration.class::cast)
                .filter(method -> method.isPrivate() && lambda.filter(inner -> inner.isDescendantOf(method)).isEmpty());
    }

    /** The calls of the method in its file; none if a method reference may run it too. */
    static Optional<List<MethodCallExpr>> callsOf(MethodDeclaration method) {
        String name = method.getNameAsString();
        Node file = method.findRootNode();
        if (file.findAll(MethodReferenceExpr.class).stream().anyMatch(reference -> reference.getIdentifier().equals(name))) {
            return Optional.empty();
        }
        return Optional.of(file.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals(name)
                        && call.getArguments().size() == method.getParameters().size()
                        && call.getScope().map(Expression::isThisExpr).orElse(true))
                .toList());
    }
}
