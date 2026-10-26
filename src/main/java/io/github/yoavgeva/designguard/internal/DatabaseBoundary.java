package io.github.yoavgeva.designguard.internal;

import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.util.List;
import java.util.Set;

/**
 * Which calls cross the database's boundary: those that leave it for another system, which a transaction can't take
 * back, and those that change data in it.
 */
final class DatabaseBoundary {

    private static final Set<String> NETWORK_PACKAGES = Set.of("java.net", "java.net.http");

    private final Effects effects;
    private final LibraryEffects libraries;

    DatabaseBoundary(Effects effects, LibraryEffects libraries) {
        this.effects = effects;
        this.libraries = libraries;
    }

    /**
     * Whether the call reaches another system than the database: the network, through {@code java.net}, or an HTTP,
     * messaging, mail or cloud client ({@link LibraryEffects#leavesTheDatabase}).
     */
    boolean leaves(JavaCodeUnitAccess<?> access) {
        return JdkEffects.isImpure(access.getTarget(), List.of())
                && NETWORK_PACKAGES.contains(access.getTargetOwner().getPackageName())
                || libraries.leavesTheDatabase(access);
    }

    /**
     * Whether the call changes data in a database: it {@link Effects#queriesRemotely} without leaving the database,
     * and calls a command, a method named for a change as {@code save} and {@code deleteById} are, or one that returns
     * nothing. One that returns another client, as {@code createNativeQuery(sql)} returns a {@code Query}, only builds
     * it, and binding a statement's parameters, as {@code setString(1, id)} does, only fills it in.
     */
    boolean writes(JavaCodeUnitAccess<?> access) {
        return effects.queriesRemotely(access) && !leaves(access)
                && !JdkPassiveCalls.readsOrBindsJdbcData(access.getTarget()) && access.getTarget().resolveMember()
                .filter(member -> member instanceof JavaMethod method && CommandMethods.isCommand(method)
                        && !effects.isImpureType(method.getRawReturnType()))
                .isPresent();
    }
}
