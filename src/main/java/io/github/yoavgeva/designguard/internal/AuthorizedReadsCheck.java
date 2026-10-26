package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
        Memo<KeyReads> reads = new Memo<>(() -> new KeyReads(classes));
        Memo<Set<String>> checking = new Memo<>(() -> RequestHandlers.checkingPermissions(classes));
        return classes.stream()
                .filter(type -> classes.selected(filter, type))
                .flatMap(type -> type.getMethods().stream()
                        .filter(method -> classes.selected(filter, method) && RequestHandlers.isHandler(type, method))
                        .flatMap(handler -> firstRead(handler, reads)
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
    private static Optional<Read> firstRead(JavaMethod handler, Memo<KeyReads> reads) {
        List<JavaParameter> parameters = handler.getParameters();
        Set<Integer> requested = IntStream.range(0, parameters.size())
                .filter(position -> isRequested(handler, parameters.get(position)))
                .boxed().collect(Collectors.toSet());
        if (requested.isEmpty()) {
            return Optional.empty();
        }
        return reads.get().flowsOf(handler).stream()
                .filter(flow -> requested.contains(flow.from()))
                .flatMap(flow -> reads.get().describe(flow).map(description -> new Read(flow.line(), description)).stream())
                .findFirst();
    }

    private static boolean isRequested(JavaMethod handler, JavaParameter parameter) {
        List<String> annotations = RequestHandlers.names(parameter.getAnnotations());
        return annotations.stream().anyMatch(REQUEST_PARAMETERS::contains)
                || annotations.isEmpty() && RequestHandlers.isSpringHandler(handler.getOwner(), handler)
                        && SIMPLE.contains(parameter.getRawType().getName());
    }

    /** A handler's read by key: the line of its call that leads there, and how the message names it. */
    private record Read(int line, String description) {
    }
}
