package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds calls inside a transaction that leave the database: a {@code @Transactional} method, or a public method of a
 * {@code @Transactional} class, that calls HTTP, messaging, mail or the network, directly or through project code
 * whose every implementation does. A rollback can't take back what went out, and the transaction holds its database
 * connection while the other system answers. A transaction that propagation suspends, {@code NOT_SUPPORTED} or
 * {@code NEVER}, isn't one. Each call is reported once per method, at its first use.
 */
public final class TransactionIoCheck {

    private static final String RULE = "no-io-in-transactions";
    private static final Set<String> TRANSACTION_ANNOTATIONS = Set.of("org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional", "javax.transaction.Transactional");
    /** Propagations that run the method without a transaction, in Spring's and Jakarta's names. */
    private static final Set<String> WITHOUT_TRANSACTION = Set.of("NOT_SUPPORTED", "NEVER");

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        SureEffects sure = code.serviceLike().sure();
        return classes.stream()
                .filter(type -> classes.selected(filter, type))
                .flatMap(type -> type.getMethods().stream()
                        .filter(method -> classes.selected(filter, method) && runsInATransaction(type, method))
                        .flatMap(method -> outsideCalls(classes, sure, type, method)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /** Whether the method's own annotation, or its public class's, starts or joins a transaction. */
    private static boolean runsInATransaction(JavaClass type, JavaMethod method) {
        Optional<JavaAnnotation<?>> own = transactionAnnotation(method.getAnnotations().stream());
        boolean inherits = method.getModifiers().contains(JavaModifier.PUBLIC)
                && !method.getModifiers().contains(JavaModifier.STATIC);
        return own.or(() -> inherits ? transactionAnnotation(type.getAnnotations().stream()) : Optional.empty())
                .filter(annotation -> !suspends(annotation))
                .isPresent();
    }

    private static Optional<JavaAnnotation<?>> transactionAnnotation(Stream<? extends JavaAnnotation<?>> annotations) {
        return annotations.filter(annotation -> TRANSACTION_ANNOTATIONS.contains(annotation.getRawType().getName()))
                .<JavaAnnotation<?>>map(annotation -> annotation)
                .findFirst();
    }

    /** {@code propagation = NOT_SUPPORTED} in Spring, {@code value = TxType.NEVER} in Jakarta. */
    private static boolean suspends(JavaAnnotation<?> annotation) {
        return Stream.of("propagation", "value")
                .map(annotation::get)
                .flatMap(Optional::stream)
                .anyMatch(value -> value instanceof JavaEnumConstant constant && WITHOUT_TRANSACTION.contains(constant.name()));
    }

    private static Stream<Violation> outsideCalls(ProjectClasses classes, SureEffects sure, JavaClass type, JavaMethod method) {
        return method.getCallsFromSelf().stream()
                .filter(sure::leavesTheDatabase)
                .collect(Collectors.toMap(call -> call.getTarget().getFullName(), call -> (JavaCodeUnitAccess<?>) call,
                        (first, second) -> first.getLineNumber() <= second.getLineNumber() ? first : second,
                        LinkedHashMap::new))
                .values().stream()
                .map(call -> classes.violation(RULE, type, call.getLineNumber(), ProjectClasses.nameOf(type) + "."
                                + method.getName() + "() calls " + ForbiddenCall.describe(call.getTarget())
                                + " inside a transaction, which leaves the database: a rollback can't take it back, and "
                                + "the transaction holds its connection while it waits",
                        "make the call after the commit, as a @TransactionalEventListener(phase = AFTER_COMMIT) does, or "
                                + "record it in an outbox table in the transaction and send it after"));
    }
}
