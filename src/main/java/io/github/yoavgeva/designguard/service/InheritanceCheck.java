package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.InheritancePolicy;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks how classes use inheritance, per an {@link InheritancePolicy}, and how deep hierarchies get. Exceptions
 * are skipped (they must extend {@code Exception}), as are framework-managed classes that need to be proxied.
 */
public final class InheritanceCheck {

    private static final String RULE = "inheritance";
    private static final Set<String> PROXIED = Set.of("Component", "Service", "Repository", "Controller",
            "RestController", "Configuration", "SpringBootApplication", "Entity", "MappedSuperclass", "Embeddable",
            "Aspect", "Transactional");

    private final InheritancePolicy policy;
    private final int maxDepth;

    /**
     * Configures the check.
     *
     * @param policy   how much inheritance is allowed
     * @param maxDepth the deepest allowed hierarchy, counting superclasses other than {@code Object}
     */
    public InheritanceCheck(InheritancePolicy policy, int maxDepth) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.maxDepth = maxDepth;
    }

    /**
     * Finds violations in the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        return classes.stream()
                .filter(type -> classes.selected(filter, type) && isOrdinaryClass(type))
                .flatMap(type -> Stream.of(policyViolation(classes, type), depthViolation(classes, type))
                        .flatMap(Optional::stream))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private Optional<Violation> policyViolation(ProjectClasses classes, JavaClass type) {
        String name = ProjectClasses.nameOf(type);
        if (policy == InheritancePolicy.FINAL_BY_DEFAULT && !type.getModifiers().contains(JavaModifier.FINAL)
                && type.getSubclasses().isEmpty() && Hierarchy.isConcrete(type)
                && ProjectClasses.annotationNames(type).stream().noneMatch(PROXIED::contains)) {
            return Optional.of(classes.violation(RULE, type, 0, name + " isn't final, and nothing extends it",
                    "make it final, or design it for extension and document how"));
        }
        Optional<JavaClass> concreteParent = type.getRawSuperclass()
                .filter(parent -> classes.isProject(parent) && Hierarchy.isConcrete(parent));
        if (policy == InheritancePolicy.ABSTRACT_ONLY && concreteParent.isPresent()) {
            String parent = ProjectClasses.nameOf(concreteParent.get());
            return Optional.of(classes.violation(RULE, type, 0,
                    name + " extends the concrete class " + parent + "; extend only abstract classes",
                    "make " + parent + " abstract, or use composition: give " + name + " a " + parent + " field instead"));
        }
        return Optional.empty();
    }

    private Optional<Violation> depthViolation(ProjectClasses classes, JavaClass type) {
        long depth = type.getAllRawSuperclasses().stream().filter(parent -> !parent.isEquivalentTo(Object.class)).count();
        if (depth <= maxDepth) {
            return Optional.empty();
        }
        return Optional.of(classes.violation(RULE, type, 0, ProjectClasses.nameOf(type) + " is " + depth
                        + " classes deep in its inheritance hierarchy (max " + maxDepth + ")",
                "flatten it: replace a level of inheritance with composition"));
    }

    private static boolean isOrdinaryClass(JavaClass type) {
        return !type.isInterface() && !type.isEnum() && !type.isRecord() && !type.isAnonymousClass()
                && !type.isLocalClass() && !type.isAssignableTo(Throwable.class);
    }
}
