package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import java.util.List;
import java.util.Set;

/**
 * Which calls surely touch the outside world when they are made: those that call an outside-world API, or project code
 * whose every implementation surely does. What counts as such a call is work, or only remote queries.
 */
final class SureEffects {

    private final ProjectClasses classes;
    private final Effects effects;
    private final Overrides overrides;
    private final Memo<Set<String>> withBodies;
    private final Memo<Set<String>> workingWhenRun;
    private final Memo<Set<String>> remoteWhenRun;
    private final Memo<Set<String>> outsideWhenRun;
    private final Memo<Set<String>> writingWhenRun;
    private final DatabaseBoundary boundary;

    SureEffects(ProjectClasses classes, Effects effects, Overrides overrides, List<JavaClass> candidates) {
        this.classes = classes;
        this.effects = effects;
        this.overrides = overrides;
        this.withBodies = new Memo<>(() -> CallGraph.withBodies(CallGraph.unitsOf(candidates)));
        this.workingWhenRun = new Memo<>(() -> effects.surelyWhenRun(classes, candidates,
                call -> effects.doesWork(classes.bytecode(), call)));
        this.remoteWhenRun = new Memo<>(() -> effects.surelyWhenRun(classes, candidates, effects::queriesRemotely));
        this.boundary = new DatabaseBoundary(effects, new LibraryEffects(classes));
        this.outsideWhenRun = new Memo<>(() -> effects.surelyWhenRun(classes, candidates, boundary::leaves));
        this.writingWhenRun = new Memo<>(() -> effects.surelyWhenRun(classes, candidates, boundary::writes));
    }

    /**
     * Whether making the call surely does work right away, which may be slow or fail: it calls an outside-world API
     * other than reading the clock, randomness or configuration, or project code whose every implementation's own
     * calls do. A strategy with an implementation without effects doesn't, nor does a JDK interface such as {@code
     * Iterator.hasNext()} that a project class implements with I/O. Lambdas the code creates don't count until they run.
     */
    boolean doesWork(JavaCodeUnitAccess<?> call) {
        return effects.doesWork(classes.bytecode(), call) || surely(call, workingWhenRun);
    }

    /**
     * Whether making the call surely queries a database or another machine right away, directly or through project
     * code whose every implementation does: {@link #doesWork}, for remote calls only.
     */
    boolean queriesRemotely(JavaCodeUnitAccess<?> call) {
        return effects.queriesRemotely(call) || surely(call, remoteWhenRun);
    }

    /**
     * Whether making the call surely reaches another system than the database right away, directly or through project
     * code whose every implementation does: HTTP, messaging, mail or the network ({@link DatabaseBoundary#leaves}).
     */
    boolean leavesTheDatabase(JavaCodeUnitAccess<?> call) {
        return boundary.leaves(call) || surely(call, outsideWhenRun);
    }

    /**
     * Whether making the call surely changes data in a database right away, directly or through project code whose
     * every implementation does ({@link DatabaseBoundary#writes}).
     */
    boolean writesToTheDatabase(JavaCodeUnitAccess<?> call) {
        return boundary.writes(call) || surely(call, writingWhenRun);
    }

    private boolean surely(JavaCodeUnitAccess<?> call, Memo<Set<String>> whenRun) {
        Set<String> implementations = classes.isProject(call.getTargetOwner())
                ? overrides.implementationsOf(classes.bytecode(), call, withBodies.get())
                : Set.of();
        return !implementations.isEmpty() && whenRun.get().containsAll(implementations);
    }
}
