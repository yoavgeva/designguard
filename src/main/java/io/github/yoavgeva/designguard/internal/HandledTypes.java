package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The types of value a handler handles, by which a framework picks it: the type arguments of its supertypes, as
 * {@code IntStreamSerializer extends StdSerializer<IntStream>} has, and the return types it narrows from
 * {@code Object} in the methods it overrides, as a key deserializer's {@code ZonedDateTime deserialize(String key)}
 * does. A test that names such a type passes its values to whatever the framework picks for them.
 */
final class HandledTypes {

    private static final String OBJECT = Object.class.getName();
    private static final String JAVA_LANG = "java.lang";

    private HandledTypes() {
    }

    /** Whether the test names a type the subject handles, by its full name or one the file imports or shares. */
    static boolean areNamedIn(ParsedFile file, TypeDeclaration<?> test, JavaClass subject) {
        Set<String> handled = of(subject);
        if (handled.isEmpty()) {
            return false;
        }
        Set<String> written = Stream.of(
                        test.findAll(ClassOrInterfaceType.class).stream().map(ClassOrInterfaceType::getNameWithScope),
                        test.findAll(NameExpr.class).stream().map(NameExpr::getNameAsString),
                        test.findAll(FieldAccessExpr.class).stream().map(FieldAccessExpr::toString))
                .flatMap(names -> names)
                .collect(Collectors.toSet());
        return handled.stream().anyMatch(type -> written.contains(type) || written.contains(simpleName(type))
                && isInScope(file, type));
    }

    private static Set<String> of(JavaClass handler) {
        Stream<JavaClass> arguments = Stream.concat(handler.getSuperclass().stream(), handler.getInterfaces().stream())
                .filter(JavaParameterizedType.class::isInstance)
                .flatMap(type -> ((JavaParameterizedType) type).getActualTypeArguments().stream())
                .filter(argument -> argument instanceof JavaClass || argument instanceof JavaParameterizedType)
                .map(JavaType::toErasure);
        Stream<JavaClass> narrowed = handler.getMethods().stream()
                .filter(method -> !method.getModifiers().contains(JavaModifier.SYNTHETIC) && narrowsFromObject(handler, method))
                .map(JavaMethod::getRawReturnType);
        return Stream.concat(arguments, narrowed)
                .filter(type -> !type.isPrimitive() && !OBJECT.equals(type.getName()))
                .map(type -> type.getName().replace('$', '.'))
                .collect(Collectors.toSet());
    }

    /** Whether the method overrides one that returns {@code Object} with a narrower type. */
    private static boolean narrowsFromObject(JavaClass handler, JavaMethod method) {
        return !OBJECT.equals(method.getRawReturnType().getName())
                && Stream.concat(handler.getAllRawSuperclasses().stream(), handler.getAllRawInterfaces().stream())
                        .flatMap(type -> type.getMethods().stream())
                        .anyMatch(inherited -> inherited.getName().equals(method.getName())
                                && inherited.getRawParameterTypes().equals(method.getRawParameterTypes())
                                && OBJECT.equals(inherited.getRawReturnType().getName()));
    }

    private static String simpleName(String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }

    /** Whether a simple name in the file means the type: it imports the type or its package, or shares its package. */
    private static boolean isInScope(ParsedFile file, String type) {
        String packageName = type.substring(0, Math.max(type.lastIndexOf('.'), 0));
        String filePackage = file.unit().getPackageDeclaration().map(PackageDeclaration::getNameAsString).orElse("");
        return JAVA_LANG.equals(packageName) || filePackage.equals(packageName)
                || file.unit().getImports().stream().filter(declaration -> !declaration.isStatic())
                        .map(ImportDeclaration::getNameAsString)
                        .anyMatch(imported -> imported.equals(type) || imported.equals(packageName));
    }
}
