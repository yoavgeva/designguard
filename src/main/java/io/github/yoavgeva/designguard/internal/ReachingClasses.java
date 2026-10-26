package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.body.TypeDeclaration;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The classes that depend on a class, directly or through others, worked out once for each class looked up. */
final class ReachingClasses {

    private final Map<JavaClass, Set<JavaClass>> reaching = new ConcurrentHashMap<>();

    /**
     * Whether a project class the test uses, other than through a mock, reaches the subject through any chain of calls
     * and references, as an ObjectMapper reaches the serializers its factory picks by the type of value.
     */
    boolean isReachedByAClassUsedIn(ParsedFile file, TypeDeclaration<?> test, JavaClass subject, SourceTypes types) {
        Set<JavaClass> reachers = reaching.computeIfAbsent(subject, ReachingClasses::find);
        return UsedClasses.named(file, test, subject, types).stream().anyMatch(reachers::contains);
    }

    private static Set<JavaClass> find(JavaClass target) {
        Set<JavaClass> found = new HashSet<>();
        Deque<JavaClass> pending = new ArrayDeque<>(List.of(target));
        while (!pending.isEmpty()) {
            pending.pop().getDirectDependenciesToSelf().stream()
                    .map(Dependency::getOriginClass)
                    .filter(found::add)
                    .forEach(pending::push);
        }
        return Set.copyOf(found);
    }
}
