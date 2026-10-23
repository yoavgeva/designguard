package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.PackageMatcher;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * Keeps a kind of class out of some packages: classes in the guarded packages must not depend on classes the
 * predicate marks, such as infrastructure exceptions in the domain, or persistence entities in the web layer.
 */
public final class BoundaryCheck {

    private static final Set<String> INFRASTRUCTURE = Set.of("java.sql", "java.io", "java.net", "javax.naming",
            "jakarta.persistence", "javax.persistence", "org.springframework.dao", "org.springframework.jdbc",
            "org.springframework.web", "org.springframework.transaction", "org.hibernate", "com.fasterxml.jackson",
            "org.apache.kafka", "software.amazon.awssdk", "com.mongodb", "redis.clients", "io.lettuce", "org.jooq");
    private static final Set<String> ENTITY_ANNOTATIONS = Set.of("Entity", "Embeddable", "MappedSuperclass");
    private static final Set<String> FRAMEWORK_PACKAGES = Set.of("org.springframework", "jakarta.persistence",
            "javax.persistence", "jakarta.servlet", "javax.servlet", "jakarta.ws.rs", "javax.ws.rs", "jakarta.ejb",
            "javax.ejb", "jakarta.transaction", "javax.transaction", "jakarta.enterprise", "javax.enterprise",
            "jakarta.inject", "javax.inject", "jakarta.validation", "javax.validation", "jakarta.json", "javax.json",
            "jakarta.xml.bind", "javax.xml.bind", "org.hibernate", "com.fasterxml.jackson", "com.google.gson",
            "com.google.inject", "dagger", "io.micronaut", "io.quarkus", "org.eclipse.microprofile", "io.vertx",
            "io.dropwizard");

    private final String rule;
    private final List<String> guardedPackages;
    private final BiPredicate<CodeBase, JavaClass> forbidden;
    private final String what;
    private final String fix;

    private BoundaryCheck(String rule, List<String> guardedPackages, BiPredicate<CodeBase, JavaClass> forbidden,
                          String what, String fix) {
        this.rule = rule;
        this.guardedPackages = List.copyOf(guardedPackages);
        this.forbidden = forbidden;
        this.what = what;
        this.fix = fix;
    }

    /**
     * Keeps infrastructure exceptions (JDBC, I/O, JPA, Spring data access, Jackson, Kafka, AWS... and the
     * configured impure packages) out of the guarded packages.
     *
     * @param domainPackages ArchUnit package patterns of the domain
     * @return the check
     */
    public static BoundaryCheck infrastructureExceptions(List<String> domainPackages) {
        return new BoundaryCheck("domain-exceptions", domainPackages, BoundaryCheck::isInfrastructureException,
                "the infrastructure exception", "catch it in an adapter and throw a domain exception instead");
    }

    /**
     * Keeps persistence entities ({@code @Entity}, {@code @Embeddable}, {@code @MappedSuperclass}) out of the
     * guarded packages.
     *
     * @param apiPackages ArchUnit package patterns of the API, e.g. {@code ..web..}
     * @return the check
     */
    public static BoundaryCheck entities(List<String> apiPackages) {
        return new BoundaryCheck("entities-stay-behind", apiPackages,
                (code, target) -> code.projectClasses().isProject(target)
                        && ProjectClasses.annotationNames(target).stream().anyMatch(ENTITY_ANNOTATIONS::contains),
                "the entity", "map it to a DTO or a domain type before it leaves the persistence layer");
    }

    /**
     * Keeps frameworks out of the guarded packages: Spring, Jakarta EE and its javax predecessors (persistence,
     * servlets, REST, CDI, injection, validation, JSON binding), Hibernate, Jackson, Gson, Guice, Dagger, Micronaut,
     * Quarkus, MicroProfile, Vert.x and Dropwizard, annotations included.
     *
     * @param domainPackages ArchUnit package patterns of the domain
     * @return the check
     */
    public static BoundaryCheck frameworks(List<String> domainPackages) {
        return new BoundaryCheck("frameworks-stay-out", domainPackages,
                (code, target) -> inAny(FRAMEWORK_PACKAGES, target.getPackageName()),
                "the framework type", "keep the domain plain Java: move the framework code to an adapter and map at the"
                        + " boundary");
    }

    /**
     * Finds forbidden dependencies from guarded classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        return classes.stream()
                .filter(origin -> isGuarded(origin) && classes.selected(filter, origin))
                .flatMap(origin -> classes.bytecode().dependenciesFrom(origin)
                        .filter(dependency -> forbidden.test(code, dependency.target()))
                        .collect(Collectors.groupingBy(dependency -> dependency.target().getName(),
                                Collectors.minBy(Comparator.comparingInt(ClassDependency::line))))
                        .values().stream()
                        .flatMap(Optional::stream)
                        .map(dependency -> violation(classes, origin, dependency)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private Violation violation(ProjectClasses classes, JavaClass origin, ClassDependency dependency) {
        return classes.violation(rule, origin, dependency.line(),
                ProjectClasses.nameOf(origin) + " uses " + what + " " + ProjectClasses.nameOf(dependency.target()), fix);
    }

    private boolean isGuarded(JavaClass javaClass) {
        return guardedPackages.stream().anyMatch(pattern -> PackageMatcher.of(pattern).matches(javaClass.getPackageName()));
    }

    private static boolean isInfrastructureException(CodeBase code, JavaClass target) {
        String packageName = target.getPackageName();
        boolean infrastructure = inAny(INFRASTRUCTURE, packageName)
                || code.settings().impurePackages().stream()
                        .anyMatch(pattern -> PackageMatcher.of(pattern).matches(packageName));
        return infrastructure && target.isAssignableTo(Throwable.class);
    }

    private static boolean inAny(Set<String> prefixes, String packageName) {
        return prefixes.stream().anyMatch(prefix -> packageName.equals(prefix) || packageName.startsWith(prefix + "."));
    }
}
