package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds public methods of public classes that take or return untyped data: {@code Object}, a map to {@code Object}
 * such as {@code Map<String, Object>}, or a JSON tree from Jackson, Gson, org.json or Jakarta JSON. A record names
 * the fields and their types, so callers and the compiler know what to pass. Type variables, inherited signatures
 * such as {@code equals(Object)}, AspectJ {@code @Around} advice, which returns whatever the advised method returns,
 * and wiring code are skipped.
 */
public final class UntypedApiCheck {

    private static final String RULE = "typed-apis";
    private static final String AROUND_ADVICE = "org.aspectj.lang.annotation.Around";
    private static final int MAP_TYPE_ARGUMENTS = 2;
    private static final Set<String> JSON_TREES = Set.of("com.fasterxml.jackson.databind.JsonNode",
            "com.fasterxml.jackson.databind.node.ObjectNode", "com.fasterxml.jackson.databind.node.ArrayNode",
            "org.json.JSONObject", "org.json.JSONArray", "com.google.gson.JsonElement", "com.google.gson.JsonObject",
            "com.google.gson.JsonArray", "jakarta.json.JsonObject", "jakarta.json.JsonValue", "javax.json.JsonObject",
            "javax.json.JsonValue");

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        WiringCode wiring = code.wiring();
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && type.getModifiers().contains(JavaModifier.PUBLIC)
                        && !wiring.contains(type))
                .flatMap(type -> type.getMethods().stream()
                        .filter(method -> isPublicApi(type, method) && classes.selected(filter, method))
                        .flatMap(method -> untyped(classes, method).map(found -> classes.violation(RULE, type,
                                method.getSourceCodeLocation().getLineNumber(),
                                ProjectClasses.nameOf(type) + "." + method.getName() + "() " + found,
                                "define a record with named, typed fields and use it instead"))))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean isPublicApi(JavaClass type, JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        return modifiers.contains(JavaModifier.PUBLIC) && !modifiers.contains(JavaModifier.SYNTHETIC)
                && !modifiers.contains(JavaModifier.BRIDGE) && !Hierarchy.isInherited(type, method)
                && !method.isAnnotatedWith(AROUND_ADVICE);
    }

    private static Stream<String> untyped(ProjectClasses classes, JavaMethod method) {
        Stream<String> taken = method.getParameterTypes().stream()
                .filter(type -> isUntyped(classes, type))
                .map(type -> "takes an untyped " + describe(type));
        Stream<String> returned = Stream.of(method.getReturnType())
                .filter(type -> isUntyped(classes, type))
                .map(type -> "returns an untyped " + describe(type));
        return Stream.concat(taken, returned);
    }

    /** Whether the type is {@code Object}, a map to objects, or a JSON tree of a library the project uses, not its own. */
    private static boolean isUntyped(ProjectClasses classes, JavaType type) {
        if (type instanceof JavaParameterizedType parameterized) {
            List<JavaType> arguments = parameterized.getActualTypeArguments();
            return parameterized.toErasure().isAssignableTo(Map.class) && arguments.size() == MAP_TYPE_ARGUMENTS
                    && isObject(arguments.get(1));
        }
        return isObject(type) || JSON_TREES.contains(type.getName()) && !classes.isProject(type.toErasure());
    }

    /** Exactly {@code Object}, not a type variable that erases to it. */
    private static boolean isObject(JavaType type) {
        return type instanceof JavaClass javaClass && javaClass.isEquivalentTo(Object.class);
    }

    /** The type as written, with simple names, e.g. {@code Map<String, Object>}. */
    private static String describe(JavaType type) {
        if (type instanceof JavaParameterizedType parameterized) {
            return parameterized.toErasure().getSimpleName() + parameterized.getActualTypeArguments().stream()
                    .map(UntypedApiCheck::describe)
                    .collect(Collectors.joining(", ", "<", ">"));
        }
        return type instanceof JavaClass javaClass ? javaClass.getSimpleName() : type.getName();
    }
}
