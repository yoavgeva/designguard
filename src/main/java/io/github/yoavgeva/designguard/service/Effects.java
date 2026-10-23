package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.PackageMatcher;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which code touches the outside world: I/O, the clock, randomness, the environment, threads or processes, or a
 * configured impure package. A method, constructor or lambda is effectful if it uses such an API, reads a field
 * holding such a resource, or calls project code that is effectful; a call through an interface counts as
 * effectful if any implementation is. Using {@code Path}, {@code URI} or {@code File} as data is not I/O.
 */
final class Effects {

    private static final Set<String> IMPURE_PACKAGES =
            Set.of("java.io", "java.nio.file", "java.nio.channels", "java.net", "java.net.http", "java.sql", "javax.sql");
    private static final Set<String> IMPURE_TYPES = Set.of("java.time.Clock", "java.time.InstantSource",
            "java.util.Random", "java.security.SecureRandom", "java.util.concurrent.ThreadLocalRandom",
            "java.util.random.RandomGenerator", "java.lang.Thread", "java.lang.ProcessBuilder", "java.lang.Runtime",
            "java.lang.IO");
    private static final Set<String> IMPURE_CALLS = Set.of("java.lang.System.currentTimeMillis",
            "java.lang.System.nanoTime", "java.lang.System.getenv", "java.lang.System.getProperty",
            "java.lang.Math.random", "java.util.UUID.randomUUID");
    private static final Map<String, Set<String>> IO_ON_VALUE_TYPES = Map.of(
            "java.nio.file.Path", Set.of("toRealPath", "register"),
            "java.nio.file.Paths", Set.of(),
            "java.net.URI", Set.of(),
            "java.net.URL", Set.of("openConnection", "openStream", "getContent"),
            "java.io.File", Set.of("exists", "isFile", "isDirectory", "canRead", "canWrite", "length", "lastModified",
                    "list", "listFiles", "delete", "deleteOnExit", "mkdir", "mkdirs", "createNewFile", "renameTo",
                    "getCanonicalPath", "getCanonicalFile", "setLastModified", "setReadOnly"),
            "java.io.Serializable", Set.of());

    private final List<PackageMatcher> extraImpurePackages;

    Effects(List<String> extraImpurePackages) {
        this.extraImpurePackages = extraImpurePackages.stream().map(PackageMatcher::of).toList();
    }

    /** Whether using this type at all means touching the outside world, like {@code HttpClient} or {@code Random}. */
    boolean isImpureType(JavaClass type) {
        String packageName = type.getPackageName();
        return ServiceLikeClasses.canBeServiceLike(type) && !IO_ON_VALUE_TYPES.containsKey(type.getName())
                && (IMPURE_PACKAGES.contains(packageName)
                        || IMPURE_TYPES.contains(type.getName())
                        || extraImpurePackages.stream().anyMatch(matcher -> matcher.matches(packageName)));
    }

    /** Names of the classes with at least one effectful method, constructor or lambda. */
    Set<String> effectfulClasses(ProjectClasses classes, List<JavaClass> candidates) {
        return effectful(classes, candidates, Effects::implementations);
    }

    /**
     * Names of the classes whose own code is effectful: a call counts only through the method it names, not through
     * overrides. Effects of an implementation the class is given, behind an interface or abstract method, don't count.
     */
    Set<String> effectfulOnTheirOwn(ProjectClasses classes, List<JavaClass> candidates) {
        return effectful(classes, candidates, target -> Stream.of(target.getFullName()));
    }

    private Set<String> effectful(ProjectClasses classes, List<JavaClass> candidates,
            Function<CodeUnitAccessTarget, Stream<String>> dispatch) {
        List<JavaCodeUnit> units = candidates.stream().flatMap(type -> type.getCodeUnits().stream()).toList();
        Map<String, Set<String>> callees = units.stream().collect(Collectors.toMap(JavaCodeUnit::getFullName,
                unit -> projectCallees(classes, unit, dispatch), (first, second) -> first));
        Set<String> effectful = units.stream().filter(this::isDirectlyEffectful).map(JavaCodeUnit::getFullName)
                .collect(Collectors.toCollection(HashSet::new));
        boolean changed = true;
        while (changed) {
            List<String> reached = callees.entrySet().stream()
                    .filter(entry -> !effectful.contains(entry.getKey())
                            && entry.getValue().stream().anyMatch(effectful::contains))
                    .map(Map.Entry::getKey)
                    .toList();
            changed = effectful.addAll(reached);
        }
        return units.stream().filter(unit -> effectful.contains(unit.getFullName()))
                .map(unit -> unit.getOwner().getName()).collect(Collectors.toSet());
    }

    private static Set<String> projectCallees(ProjectClasses classes, JavaCodeUnit unit,
            Function<CodeUnitAccessTarget, Stream<String>> dispatch) {
        return accesses(unit)
                .map(JavaCodeUnitAccess::getTarget)
                .filter(target -> classes.isProject(target.getOwner()))
                .flatMap(dispatch)
                .collect(Collectors.toSet());
    }

    /** The target itself, plus every override in a project subclass: a call may dispatch to any of them. */
    private static Stream<String> implementations(CodeUnitAccessTarget target) {
        Stream<String> overrides = target.getOwner().getAllSubclasses().stream()
                .flatMap(subclass -> subclass.getMethods().stream())
                .filter(method -> method.getName().equals(target.getName())
                        && method.getRawParameterTypes().equals(target.getRawParameterTypes()))
                .map(JavaCodeUnit::getFullName);
        return Stream.concat(Stream.of(target.getFullName()), overrides);
    }

    private boolean isDirectlyEffectful(JavaCodeUnit unit) {
        boolean impureAccess = accesses(unit).anyMatch(access -> isImpure(access.getTarget()));
        boolean usesImpureResource = unit.getFieldAccesses().stream()
                .anyMatch(access -> "java.lang.System".equals(access.getTargetOwner().getName())
                        || isImpureType(access.getTarget().getRawType()));
        return impureAccess || usesImpureResource;
    }

    /** Calls, constructor calls and method references the code unit makes. */
    private static Stream<JavaCodeUnitAccess<?>> accesses(JavaCodeUnit unit) {
        return Stream.concat(unit.getCallsFromSelf().stream(), unit.getCodeUnitReferencesFromSelf().stream());
    }

    private boolean isImpure(CodeUnitAccessTarget target) {
        JavaClass owner = target.getOwner();
        String name = owner.getName();
        if (!ServiceLikeClasses.canBeServiceLike(owner)) {
            return false;
        }
        if (IO_ON_VALUE_TYPES.containsKey(name)) {
            return IO_ON_VALUE_TYPES.get(name).contains(target.getName());
        }
        return isImpureType(owner)
                || IMPURE_CALLS.contains(name + "." + target.getName())
                || "java.time".equals(owner.getPackageName()) && "now".equals(target.getName());
    }
}
