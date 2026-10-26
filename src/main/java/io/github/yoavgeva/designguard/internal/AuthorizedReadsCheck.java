package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Finds request handlers that read a record by a key the request sends, with nothing checking that the user asking may
 * see it: what a {@code @PathVariable}, {@code @RequestParam}, {@code @PathParam} or {@code @QueryParam} parameter, or
 * a Spring handler's unannotated simple parameter, holds flows, converted or not, through the project code it's passed
 * to, into the key of a Spring Data repository's {@code findById}, {@code getById}, {@code getReferenceById} or
 * {@code getOne}, an {@code EntityManager}'s {@code find} or {@code getReference}, or MyBatis-Plus's
 * {@code selectById} or {@code getById}. Anyone can then read anyone's record by changing the id in the URL. A handler
 * passes on the terms {@code writesAreAuthorized} reads; so does a read made in a method that checks the user itself,
 * as a helper that loads a record and then compares its owner with the current user does.
 */
public final class AuthorizedReadsCheck {

    private static final String RULE = "reads-are-authorized";
    private static final String REPOSITORY = "org.springframework.data.repository.Repository";
    private static final Set<String> REPOSITORY_FETCHES = Set.of("findById", "getById", "getReferenceById", "getOne");
    private static final Set<String> ENTITY_MANAGERS = Set.of("jakarta.persistence.EntityManager",
            "javax.persistence.EntityManager");
    private static final Set<String> ENTITY_MANAGER_FETCHES = Set.of("find", "getReference");
    private static final Set<String> MYBATIS = Set.of("com.baomidou.mybatisplus.core.mapper.BaseMapper",
            "com.baomidou.mybatisplus.extension.service.IService");
    private static final Set<String> MYBATIS_FETCHES = Set.of("selectById", "getById");
    private static final Set<String> REQUEST_PARAMETERS = Set.of("PathVariable", "RequestParam", "MatrixVariable",
            "PathParam", "QueryParam", "MatrixParam");
    private static final Set<String> SIMPLE = Set.of("java.lang.String", "java.lang.Long", "java.lang.Integer",
            "java.lang.Short", "java.util.UUID", "long", "int", "short");

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        Bytecode bytecode = classes.bytecode();
        Map<String, JavaClass> byName = classes.stream()
                .collect(Collectors.toMap(JavaClass::getName, type -> type, (first, second) -> first));
        List<JavaCodeUnit> units = CallGraph.unitsOf(classes.stream().toList()).stream()
                .sorted(Comparator.comparing(AuthorizedReadsCheck::key)).toList();
        Map<String, Map<Integer, String>> reading = reading(units, bytecode, byName);
        Memo<Set<String>> checking = new Memo<>(() -> RequestHandlers.checkingPermissions(classes));
        return classes.stream()
                .filter(type -> classes.selected(filter, type))
                .flatMap(type -> type.getMethods().stream()
                        .filter(method -> classes.selected(filter, method) && RequestHandlers.isHandler(type, method))
                        .flatMap(handler -> firstRead(bytecode, handler, reading, byName)
                                .filter(read -> !RequestHandlers.isChecked(type, handler, checking.get()))
                                .map(read -> classes.violation(RULE, type, read.line(), ProjectClasses.nameOf(type) + "."
                                                + handler.getName() + "() reads a record by the id the request sends through "
                                                + read.description() + ": nothing checks that the user asking may see it",
                                        "take the current user, as an @AuthenticationPrincipal parameter, and scope the "
                                                + "query to it, or check permissions with @PreAuthorize"))
                                .stream()))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /** The handler's first call, by line, that a request parameter flows into and that reads a record by it. */
    private static Optional<Read> firstRead(Bytecode bytecode, JavaMethod handler, Map<String, Map<Integer, String>> reading,
            Map<String, JavaClass> byName) {
        List<JavaParameter> parameters = handler.getParameters();
        Set<Integer> requested = IntStream.range(0, parameters.size())
                .filter(position -> isRequested(handler, parameters.get(position)))
                .boxed().collect(Collectors.toSet());
        return bytecode.parameterFlowsOf(handler).stream()
                .filter(flow -> requested.contains(flow.from()))
                .flatMap(flow -> describe(flow, reading, byName).map(description -> new Read(flow.line(), description)).stream())
                .findFirst();
    }

    private static boolean isRequested(JavaMethod handler, JavaParameter parameter) {
        List<String> annotations = RequestHandlers.names(parameter.getAnnotations());
        return annotations.stream().anyMatch(REQUEST_PARAMETERS::contains)
                || annotations.isEmpty() && RequestHandlers.isSpringHandler(handler.getOwner(), handler)
                        && SIMPLE.contains(parameter.getRawType().getName());
    }

    /**
     * For each project method, the parameters that flow into a read by key, with what the read is, until no more are
     * found. A method that checks the user itself checks what it reads, so passes none on.
     */
    private static Map<String, Map<Integer, String>> reading(List<JavaCodeUnit> units, Bytecode bytecode,
            Map<String, JavaClass> byName) {
        Map<String, Map<Integer, String>> found = new HashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (JavaCodeUnit unit : units) {
                if (unit instanceof JavaMethod method && RequestHandlers.readsTheUser(method.getOwner(), method)) {
                    continue;
                }
                Map<Integer, String> reads = found.computeIfAbsent(key(unit), name -> new HashMap<>());
                for (ParameterFlows.Flow flow : bytecode.parameterFlowsOf(unit)) {
                    if (!reads.containsKey(flow.from())) {
                        Optional<String> read = describe(flow, found, byName);
                        if (read.isPresent()) {
                            reads.put(flow.from(), read.get());
                            changed = true;
                        }
                    }
                }
            }
        }
        return found;
    }

    /** How a message names the read a flow leads to: the fetch itself, or the project method that reaches it. */
    private static Optional<String> describe(ParameterFlows.Flow flow, Map<String, Map<Integer, String>> found,
            Map<String, JavaClass> byName) {
        Optional<JavaClass> owner = Optional.ofNullable(byName.get(flow.owner().replace('/', '.')));
        String called = simpleName(flow.owner()) + "." + flow.name() + "()";
        if (owner.map(type -> fetches(type, flow)).orElse(fetchesOutside(flow))) {
            return Optional.of(called);
        }
        return owner.stream()
                .flatMap(type -> Stream.concat(Stream.of(type), type.getAllSubclasses().stream()))
                .map(type -> found.getOrDefault(key(type, flow.name(), flow.descriptor()), Map.of()).get(flow.to()))
                .filter(read -> read != null)
                .findFirst()
                .map(read -> called + ", which calls " + read);
    }

    /** Whether a flow gives the key to a project repository's or mapper's read by key. */
    private static boolean fetches(JavaClass owner, ParameterFlows.Flow flow) {
        return flow.to() == 0 && (REPOSITORY_FETCHES.contains(flow.name()) && isA(owner, Set.of(REPOSITORY))
                || MYBATIS_FETCHES.contains(flow.name()) && isA(owner, MYBATIS));
    }

    /** Whether a flow gives the key to a library's read by key, as {@code EntityManager.find(type, id)} takes it. */
    private static boolean fetchesOutside(ParameterFlows.Flow flow) {
        return flow.to() == 1 && ENTITY_MANAGER_FETCHES.contains(flow.name())
                && ENTITY_MANAGERS.contains(flow.owner().replace('/', '.'));
    }

    private static boolean isA(JavaClass type, Set<String> supertypes) {
        return Stream.concat(Stream.of(type), Stream.concat(type.getAllRawSuperclasses().stream(),
                type.getAllRawInterfaces().stream())).anyMatch(candidate -> supertypes.contains(candidate.getName()));
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
    }

    private static String key(JavaCodeUnit unit) {
        return key(unit.getOwner(), unit.getName(), unit.getDescriptor());
    }

    private static String key(JavaClass owner, String name, String descriptor) {
        return owner.getName() + "." + name + descriptor;
    }

    /** A handler's read by key: the line of its call that leads there, and how the message names it. */
    private record Read(int line, String description) {
    }
}
