package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What the project's compiled code uses and creates: ArchUnit's dependencies, plus what ArchUnit doesn't import from
 * the class files, see {@link ClassFileFacts}.
 */
final class Bytecode {

    private final JavaClasses classes;
    private final JavaClasses matchedOutside;
    private final Map<String, ClassFileFacts> facts;
    private final Map<String, List<JavaClass>> usersNotImported;

    /**
     * Combines ArchUnit's classes with their class files' facts.
     *
     * @param classes        the project classes
     * @param matchedOutside classes outside the project that the project's switches match on
     * @param facts          each project class's {@link ClassFileFacts}, by class name
     */
    Bytecode(JavaClasses classes, JavaClasses matchedOutside, Map<String, ClassFileFacts> facts) {
        this.classes = classes;
        this.matchedOutside = matchedOutside;
        this.facts = Map.copyOf(facts);
        this.usersNotImported = classes.stream()
                .flatMap(origin -> Stream.concat(factsOf(origin).switchLabels().stream(),
                                factsOf(origin).namedClasses().stream())
                        .map(reference -> Map.entry(reference.className(), origin)))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    /**
     * What the class's code depends on: the dependencies ArchUnit imports, the classes its switches match on, and, at
     * line 0, the other classes its class file names, like those whose constants it reads. A class named only as the
     * outer class of one it uses already is left out.
     */
    Stream<ClassDependency> dependenciesFrom(JavaClass origin) {
        Stream<ClassDependency> imported = origin.getDirectDependenciesFromSelf().stream()
                .map(dependency -> new ClassDependency(origin, dependency.getTargetClass(),
                        dependency.getSourceCodeLocation().getLineNumber()));
        List<ClassDependency> located = Stream.concat(imported, references(origin, factsOf(origin).switchLabels()))
                .toList();
        Stream<ClassDependency> namedOnly = references(origin, factsOf(origin).namedClasses())
                .filter(named -> located.stream()
                        .noneMatch(dependency -> Hierarchy.isSelfOrNested(named.target(), dependency.target())));
        return Stream.concat(located.stream(), namedOnly);
    }

    /** The project classes whose code depends on the class, or whose class files name it. */
    Stream<JavaClass> usersOf(JavaClass target) {
        return Stream.concat(target.getDirectDependenciesToSelf().stream().map(Dependency::getOriginClass),
                usersNotImported.getOrDefault(target.getName(), List.of()).stream());
    }

    /** The project classes the class's code creates with {@code new}, at the line of each {@code new}. */
    Stream<ClassDependency> creationsIn(JavaClass origin) {
        return references(origin, factsOf(origin).creations())
                .filter(creation -> classes.contain(creation.target().getName()));
    }

    /**
     * The calls a method or constructor makes itself. ArchUnit also counts the calls in the body of a lambda it
     * creates, which run only when the lambda does, so for a method that creates lambdas the class file decides.
     */
    Stream<JavaCall<?>> callsMadeBy(JavaCodeUnit unit) {
        Set<JavaCall<?>> calls = unit.getCallsFromSelf();
        return Optional.ofNullable(factsOf(unit.getOwner()).directCalls().get(unit.getName() + unit.getDescriptor()))
                .map(made -> calls.stream().filter(call -> made.contains(ClassFileFacts.callKey(
                        call.getTargetOwner().getName(), call.getName(), call.getLineNumber()))))
                .orElseGet(calls::stream);
    }

    /** The fields a method or constructor reads if it compares anything with {@code null}, as lazy initialization does. */
    Set<String> nullGuardedReadsIn(JavaCodeUnit unit) {
        return factsOf(unit.getOwner()).nullGuardedReads().getOrDefault(unit.getName() + unit.getDescriptor(), Set.of());
    }

    /**
     * The calls and method references a method or constructor makes once per item: in a loop, or in a lambda or method
     * reference it hands to a stream, {@code forEach} or a similar per-item method.
     */
    Stream<JavaCodeUnitAccess<?>> perItemAccesses(JavaCodeUnit unit) {
        ClassFileFacts.Loops loops = factsOf(unit.getOwner()).loops();
        String key = unit.getName() + unit.getDescriptor();
        String ownName = unit.getOwner().getName() + ".";
        Set<String> handles = loops.lambdas().getOrDefault(key, Set.of());
        Set<String> calls = Stream.concat(loops.calls().getOrDefault(key, Set.of()).stream(), handles.stream()
                        .filter(handle -> handle.startsWith(ownName + "lambda$"))
                        .flatMap(handle -> loops.lambdaBodies().getOrDefault(handle.substring(ownName.length()), Set.of())
                                .stream()))
                .collect(Collectors.toSet());
        Set<String> references = handles.stream().map(handle -> handle.substring(0, handle.indexOf('(')))
                .collect(Collectors.toSet());
        Stream<JavaCodeUnitAccess<?>> perItemCalls = unit.getCallsFromSelf().stream()
                .filter(call -> calls.contains(ClassFileFacts.callKey(
                        call.getTargetOwner().getName(), call.getName(), call.getLineNumber())))
                .map(call -> call);
        Stream<JavaCodeUnitAccess<?>> perItemReferences = unit.getCodeUnitReferencesFromSelf().stream()
                .filter(reference -> references.contains(reference.getTargetOwner().getName() + "." + reference.getName()))
                .map(reference -> reference);
        return Stream.concat(perItemCalls, perItemReferences);
    }

    /** The oldest Java version the top-level classes were compiled for; empty if none was read. */
    OptionalInt oldestRelease() {
        return classes.stream()
                .filter(JavaClass::isTopLevelClass)
                .mapToInt(type -> factsOf(type).release())
                .filter(release -> release > 0)
                .min();
    }

    private Stream<ClassDependency> references(JavaClass origin, List<ClassFileFacts.Reference> references) {
        return references.stream().flatMap(reference -> classNamed(reference.className()).stream()
                .map(target -> new ClassDependency(origin, target, reference.line())));
    }

    private Optional<JavaClass> classNamed(String name) {
        if (classes.contain(name)) {
            return Optional.of(classes.get(name));
        }
        return matchedOutside.contain(name) ? Optional.of(matchedOutside.get(name)) : Optional.empty();
    }

    private ClassFileFacts factsOf(JavaClass javaClass) {
        return facts.getOrDefault(javaClass.getName(), ClassFileFacts.UNREAD);
    }
}
