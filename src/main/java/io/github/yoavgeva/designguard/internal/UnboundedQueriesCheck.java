package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds request handlers that load every row of a table: a Spring MVC or JAX-RS handler that calls, directly or
 * through project code, a Spring Data repository's {@code findAll()} or {@code findAll(Sort)}, or a MyBatis-Plus
 * service's {@code list()}. Each request then loads more as the table grows, until it times out. A
 * {@code findAll(Pageable)}, a query method with criteria, or a {@code findAll} given an example or a specification
 * filters or pages, and passes.
 */
public final class UnboundedQueriesCheck {

    private static final String RULE = "no-unbounded-queries";
    private static final String REPOSITORY = "org.springframework.data.repository.Repository";
    private static final String SORT = "org.springframework.data.domain.Sort";
    private static final String MYBATIS_SERVICE = "com.baomidou.mybatisplus.extension.service.IService";
    /** Calls by line, and on one line by what they call, so a report names the same call on every run. */
    private static final Comparator<JavaCodeUnitAccess<?>> IN_CODE_ORDER = Comparator
            .<JavaCodeUnitAccess<?>>comparingInt(JavaCodeUnitAccess::getLineNumber)
            .thenComparing(call -> ForbiddenCall.describe(call.getTarget()));

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        Overrides overrides = new Overrides();
        List<JavaCodeUnit> units = CallGraph.unitsOf(classes.stream().toList());
        Map<String, JavaCodeUnit> byName = units.stream()
                .collect(Collectors.toMap(JavaCodeUnit::getFullName, unit -> unit, (first, second) -> first));
        Function<Stream<? extends JavaCodeUnitAccess<?>>, Set<String>> callees = accesses -> CallGraph.projectCallees(
                classes, accesses, access -> overrides.mayRun(classes.bytecode(), access));
        Memo<Set<String>> listing = new Memo<>(() -> CallGraph.reaching(units,
                unit -> callees.apply(unit.getCallsFromSelf().stream()),
                unit -> unit.getCallsFromSelf().stream().anyMatch(UnboundedQueriesCheck::listsWholeTable)));
        return classes.stream()
                .filter(type -> classes.selected(filter, type))
                .flatMap(type -> type.getMethods().stream()
                        .filter(method -> classes.selected(filter, method) && RequestHandlers.isHandler(type, method))
                        .flatMap(handler -> handler.getCallsFromSelf().stream()
                                .sorted(IN_CODE_ORDER)
                                .flatMap(call -> through(call, callees, listing, byName).stream())
                                .findFirst()
                                .map(path -> classes.violation(RULE, type, path.call().getLineNumber(),
                                        ProjectClasses.nameOf(type) + "." + handler.getName() + "() loads every row through "
                                                + path.description() + ", so each request loads more as the table grows",
                                        "take a Pageable and query a page, or filter the query to what the response shows"))
                                .stream()))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /** The whole table a handler's call lists, itself or through the project code it runs. */
    private static Optional<Path> through(JavaCodeUnitAccess<?> call,
            Function<Stream<? extends JavaCodeUnitAccess<?>>, Set<String>> callees, Memo<Set<String>> listing,
            Map<String, JavaCodeUnit> byName) {
        if (listsWholeTable(call)) {
            return Optional.of(new Path(call, describe(call)));
        }
        Set<String> runs = callees.apply(Stream.of(call));
        if (runs.stream().noneMatch(listing.get()::contains)) {
            return Optional.empty();
        }
        return firstListing(runs, callees, byName)
                .map(seed -> new Path(call, ForbiddenCall.describe(call.getTarget()) + ", which calls " + describe(seed)));
    }

    /** The first call that lists a whole table, breadth-first from the code units, in the order of their names. */
    private static Optional<JavaCodeUnitAccess<?>> firstListing(Set<String> start,
            Function<Stream<? extends JavaCodeUnitAccess<?>>, Set<String>> callees, Map<String, JavaCodeUnit> byName) {
        Deque<String> queue = new ArrayDeque<>(start.stream().sorted().toList());
        Set<String> seen = new HashSet<>(start);
        while (!queue.isEmpty()) {
            JavaCodeUnit unit = byName.get(queue.removeFirst());
            if (unit == null) {
                continue;
            }
            Optional<JavaCodeUnitAccess<?>> found = unit.getCallsFromSelf().stream()
                    .filter(UnboundedQueriesCheck::listsWholeTable)
                    .min(IN_CODE_ORDER)
                    .map(call -> call);
            if (found.isPresent()) {
                return found;
            }
            callees.apply(unit.getCallsFromSelf().stream()).stream().sorted().filter(seen::add).forEach(queue::addLast);
        }
        return Optional.empty();
    }

    /** Whether the call lists every row: a repository's {@code findAll()} or {@code findAll(Sort)}, or a service's {@code list()}. */
    private static boolean listsWholeTable(JavaCodeUnitAccess<?> call) {
        List<JavaClass> parameters = call.getTarget().getRawParameterTypes();
        boolean wholeOrSorted = parameters.isEmpty() || parameters.size() == 1 && SORT.equals(parameters.get(0).getName());
        return "findAll".equals(call.getName()) && wholeOrSorted && isA(call.getTargetOwner(), REPOSITORY)
                || "list".equals(call.getName()) && parameters.isEmpty() && isA(call.getTargetOwner(), MYBATIS_SERVICE);
    }

    private static boolean isA(JavaClass type, String supertype) {
        return Stream.concat(Stream.of(type), Stream.concat(type.getAllRawSuperclasses().stream(),
                type.getAllRawInterfaces().stream())).anyMatch(candidate -> supertype.equals(candidate.getName()));
    }

    /** {@code ProductRepository.findAll()}, naming a sort parameter as {@code findAll(Sort)} does. */
    private static String describe(JavaCodeUnitAccess<?> call) {
        String parameters = call.getTarget().getRawParameterTypes().stream().map(JavaClass::getSimpleName)
                .collect(Collectors.joining(", "));
        return ProjectClasses.nameOf(call.getTargetOwner()) + "." + call.getName() + "(" + parameters + ")";
    }

    /** The handler's call that leads to a whole table, and how the message names the way there. */
    private record Path(JavaCodeUnitAccess<?> call, String description) {
    }
}
