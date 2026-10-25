package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds interfaces and abstract classes implemented only inside the project that aren't sealed. Sealing them lets
 * the compiler check switches over them for missing cases. Functional interfaces are skipped, because lambdas
 * implement them and lambdas can't implement a sealed interface. So are the ones there to be replaced, which nothing
 * switches over: service-like ones, those a component a container manages implements, as a Spring bean's interface,
 * and those tests mock or implement with a fake, which a sealed type would refuse. So are those with an
 * implementation the build generates, as MapStruct does for a mapper, which can't be declared to fit the permits.
 */
public final class SealedHierarchyCheck {

    private static final String RULE = "sealed-hierarchies";

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        JavaSources sources = code.sources();
        ServiceLikeClasses serviceLike = code.serviceLike();
        Set<String> replacedInTests = replacedInTests(code.testSources());
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && isOpenAbstraction(type) && !type.getSubclasses().isEmpty()
                        && !serviceLike.contains(type) && !replacedInTests.contains(type.getSimpleName())
                        && type.getSubclasses().stream().noneMatch(ManagedComponents::contains))
                .filter(type -> sources.declaration(canonicalName(type)).map(declared -> !declared.isSealed()).orElse(false)
                        && type.getSubclasses().stream().allMatch(subclass -> sources.declaration(canonicalName(subclass)).isPresent()))
                .map(type -> {
                    String name = ProjectClasses.nameOf(type);
                    String permits = type.getSubclasses().stream().map(ProjectClasses::nameOf).sorted()
                            .collect(Collectors.joining(", "));
                    String keyword = type.isInterface() ? "sealed interface " : "sealed abstract class ";
                    int line = sources.declaration(canonicalName(type))
                            .map(declared -> Scopes.line(declared.declaration().getName())).orElse(0);
                    return classes.violation(RULE, type, line,
                            name + " is only implemented in this project, but isn't sealed",
                            "declare it sealed: " + keyword + name + " permits " + permits);
                })
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /** The simple names of the types tests mock or implement, as a fake does: a sealed type would refuse them. */
    private static Set<String> replacedInTests(JavaSources tests) {
        Stream<String> mocked = tests.files().stream().flatMap(file -> MockSites.in(file.unit())).map(MockSites.MockSite::typeName);
        Stream<String> implemented = tests.files().stream()
                .flatMap(file -> Stream.concat(file.unit().findAll(ClassOrInterfaceDeclaration.class).stream()
                                .flatMap(type -> Stream.concat(type.getImplementedTypes().stream(), type.getExtendedTypes().stream())),
                        file.unit().findAll(ObjectCreationExpr.class).stream()
                                .filter(creation -> creation.getAnonymousClassBody().isPresent())
                                .map(ObjectCreationExpr::getType)))
                .map(ClassOrInterfaceType::getNameAsString);
        return Stream.concat(mocked, implemented).collect(Collectors.toSet());
    }

    private static boolean isOpenAbstraction(JavaClass type) {
        boolean abstraction = type.isInterface() && !type.isAnnotation()
                || !type.isInterface() && type.getModifiers().contains(JavaModifier.ABSTRACT);
        return abstraction && !isFunctionalInterface(type);
    }

    private static boolean isFunctionalInterface(JavaClass type) {
        long abstractMethods = type.getMethods().stream()
                .filter(method -> method.getModifiers().contains(JavaModifier.ABSTRACT))
                .count();
        return type.isInterface() && (abstractMethods == 1
                || ProjectClasses.annotationNames(type).contains("FunctionalInterface"));
    }

    private static String canonicalName(JavaClass type) {
        return type.getName().replace('$', '.');
    }
}
