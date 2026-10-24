package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.type.Type;
import com.tngtech.archunit.core.domain.PackageMatcher;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Finds identifiers typed as raw strings, numbers or UUIDs in the guarded packages: fields, record components and
 * non-private method and constructor parameters named {@code id} or {@code somethingId}. With a type per identifier,
 * the compiler stops a customer ID from being passed where an order ID belongs. The identifier types themselves, named
 * {@code ...Id}, are skipped, and so are parameters of methods that override or implement another type's, whose
 * signature that type sets.
 */
public final class RawIdentifiersCheck {

    private static final String RULE = "typed-identifiers";
    /** Where a method's name moves on to the key it looks by, as in {@code findVisitById}. */
    private static final Pattern BY = Pattern.compile("By(?=[A-Z])");
    private static final String PLAIN_ID = "id";
    private static final Pattern ID_NAME = Pattern.compile("id|[a-z][A-Za-z0-9]*(Id|ID)");
    private static final Pattern ID_TYPE = Pattern.compile(".*(Id|ID)");
    private static final Set<String> RAW_TYPES = Set.of("String", "java.lang.String", "long", "Long", "java.lang.Long",
            "int", "Integer", "java.lang.Integer", "UUID", "java.util.UUID");

    private final List<PackageMatcher> guardedPackages;

    /**
     * Configures the check.
     *
     * @param guardedPackages ArchUnit package patterns to check, e.g. {@code ..domain..}
     */
    public RawIdentifiersCheck(List<String> guardedPackages) {
        this.guardedPackages = guardedPackages.stream().map(PackageMatcher::of).toList();
    }

    /**
     * Finds violations in the sources the filter selects.
     *
     * @param sources the parsed production sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .filter(file -> guardedPackages.stream().anyMatch(matcher -> matcher.matches(file.packageName())))
                .flatMap(file -> Stream.of(fields(file), components(file), parameters(file)).flatMap(found -> found))
                .filter(found -> Scopes.selected(filter, found.file(), found.node()))
                .map(RawIdentifier::violation)
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static Stream<RawIdentifier> fields(ParsedFile file) {
        return file.unit().findAll(FieldDeclaration.class).stream()
                .filter(field -> !field.isStatic())
                .flatMap(field -> ownerName(field).stream().flatMap(owner -> field.getVariables().stream()
                        .flatMap(variable -> raw(variable.getType(), variable.getNameAsString()).stream()
                                .map(type -> new RawIdentifier(file, variable, owner,
                                        owner + "." + variable.getNameAsString() + " is", variable.getNameAsString(), type)))));
    }

    private static Stream<RawIdentifier> components(ParsedFile file) {
        return file.unit().findAll(RecordDeclaration.class).stream()
                .filter(record -> !ID_TYPE.matcher(record.getNameAsString()).matches())
                .flatMap(record -> record.getParameters().stream()
                        .flatMap(component -> raw(component.getType(), component.getNameAsString()).stream()
                                .map(type -> new RawIdentifier(file, component, record.getNameAsString(),
                                        record.getNameAsString() + "." + component.getNameAsString() + " is",
                                        component.getNameAsString(), type))));
    }

    private static Stream<RawIdentifier> parameters(ParsedFile file) {
        Stream<Parameter> methods = file.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> !method.isPrivate())
                .flatMap(method -> method.getParameters().stream());
        Stream<Parameter> constructors = file.unit().findAll(ConstructorDeclaration.class).stream()
                .filter(constructor -> !constructor.isPrivate())
                .flatMap(constructor -> constructor.getParameters().stream());
        return Stream.concat(methods, constructors)
                .flatMap(parameter -> ownerName(parameter).stream().flatMap(owner -> Scopes.enclosingCallable(parameter)
                        .stream()
                        .flatMap(callable -> raw(parameter.getType(), parameter.getNameAsString()).stream()
                                .filter(type -> !overrides(callable))
                                .map(type -> new RawIdentifier(file, parameter, identified(parameter, callable, owner),
                                        Scopes.describe(callable) + " takes " + parameter.getNameAsString() + " as",
                                        parameter.getNameAsString(), type)))));
    }

    /**
     * What a parameter identifies: the thing its method names after the verb, as {@code getPet(id)} and
     * {@code findVisitById(id)} do, a repository's entity, or else the declaring type.
     */
    private static String identified(Parameter parameter, CallableDeclaration<?> callable, String owner) {
        String name = callable.getNameAsString();
        int noun = IntStream.range(0, name.length()).filter(index -> Character.isUpperCase(name.charAt(index)))
                .findFirst().orElse(name.length());
        Matcher by = BY.matcher(name);
        String named = name.substring(Math.min(noun, name.length()), by.find(noun) ? by.start() : name.length());
        if (noun > 0 && !named.isEmpty()) {
            return named;
        }
        return Scopes.enclosingType(parameter)
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .stream()
                .flatMap(type -> Stream.concat(type.getExtendedTypes().stream(), type.getImplementedTypes().stream()))
                .filter(supertype -> supertype.getNameAsString().endsWith("Repository"))
                .flatMap(supertype -> supertype.getTypeArguments().stream().flatMap(List::stream).limit(1))
                .map(entity -> entity.isClassOrInterfaceType() ? entity.asClassOrInterfaceType().getNameAsString()
                        : entity.asString())
                .findFirst()
                .orElse(owner);
    }

    /** Whether the method overrides or implements a method of another type, which sets its signature. */
    private static boolean overrides(CallableDeclaration<?> callable) {
        return callable instanceof MethodDeclaration method
                && (Scopes.hasAnnotation(method, "Override") || Resolution.overridesSupertype(method));
    }

    /** The enclosing type's name, unless the type is an identifier type itself. */
    private static Optional<String> ownerName(Node node) {
        return Scopes.enclosingType(node).map(type -> type.getNameAsString())
                .filter(name -> !ID_TYPE.matcher(name).matches());
    }

    /** The raw type's simple name, if the name is an identifier's and the type a string, number or UUID. */
    private static Optional<String> raw(Type type, String name) {
        String written = type.asString();
        if (!ID_NAME.matcher(name).matches() || !RAW_TYPES.contains(written)) {
            return Optional.empty();
        }
        return Optional.of(written.substring(written.lastIndexOf('.') + 1));
    }

    /**
     * An identifier with a raw type.
     *
     * @param file  where it is declared
     * @param node  its declaration
     * @param owner the declaring type's name
     * @param where how the message starts, e.g. {@code Order.customerId is}
     * @param name  its name
     * @param type  its raw type's simple name
     */
    private record RawIdentifier(ParsedFile file, Node node, String owner, String where, String name, String type) {

        Violation violation() {
            return Scopes.violation(RULE, file, node, where + " a raw " + type + " identifier", "declare record " + idType()
                    + "(" + type + " value) and use it, so identifiers of different things can't be swapped");
        }

        private String idType() {
            if (PLAIN_ID.equals(name)) {
                return owner + "Id";
            }
            String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            return capitalized.endsWith("ID") ? capitalized.substring(0, capitalized.length() - 1) + "d" : capitalized;
        }
    }
}
