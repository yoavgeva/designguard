package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Finds classes that depend on each other, directly or around a loop. A nested class counts as its top-level class,
 * and a type naming its own subtypes, as a sealed type's {@code permits} clause or an interface's factory method does,
 * isn't part of a cycle, nor is an annotation naming the class that handles it, as a Bean Validation constraint names
 * its validator, nor an entity's inverse side of a JPA association to the entity that maps it. Each set of classes in
 * a cycle is reported once, at the first class by name.
 */
public final class ClassCycleCheck {

    private static final String RULE = "class-cycles";
    private static final int LISTED = 10;
    private static final Set<String> PERSISTENCE = Set.of("jakarta.persistence", "javax.persistence");
    private static final Set<String> INVERSE_ASSOCIATIONS = Set.of("OneToMany", "OneToOne", "ManyToMany");

    /**
     * Finds cycles among the classes the filter selects.
     *
     * @param code   the code base
     * @param filter which classes to check
     * @return violations ordered by file
     */
    public List<Violation> check(CodeBase code, ClassFilter filter) {
        ProjectClasses classes = code.projectClasses();
        List<JavaClass> nodes = classes.stream()
                .filter(type -> type.isTopLevelClass() && classes.selected(filter, type))
                .sorted(Comparator.comparing(JavaClass::getName))
                .toList();
        Map<String, Integer> indexes = IntStream.range(0, nodes.size()).boxed()
                .collect(Collectors.toMap(index -> nodes.get(index).getName(), Function.identity()));
        Map<JavaClass, List<JavaClass>> nestedByTop = classes.stream()
                .collect(Collectors.groupingBy(ClassCycleCheck::topLevelOf));
        List<List<Integer>> successors = nodes.stream()
                .map(node -> {
                    Set<JavaClass> inverse = inverseSides(nestedByTop.getOrDefault(node, List.of(node)));
                    return nestedByTop.getOrDefault(node, List.of(node)).stream()
                            .flatMap(member -> classes.bytecode().dependenciesFrom(member))
                            .map(dependency -> topLevelOf(dependency.target()))
                            .filter(target -> !target.equals(node) && !target.isAssignableTo(node.getName())
                                    && !handles(target, node) && !inverse.contains(target))
                            .flatMap(target -> Stream.ofNullable(indexes.get(target.getName())))
                            .distinct()
                            .toList();
                })
                .toList();
        int[] components = components(successors, finishOrder(successors));
        return IntStream.range(0, nodes.size()).boxed()
                .collect(Collectors.groupingBy(index -> components[index]))
                .values().stream()
                .filter(members -> members.size() > 1)
                .map(members -> violation(classes, members.stream().map(nodes::get).toList()))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    /**
     * Whether the class handles the annotation type, which names it in an annotation of its own, as
     * {@code @Constraint(validatedBy = XssValidator.class)} names a validator and {@code @JsonSerialize(using = ...)} a
     * serializer: the handler reads the annotation, and the two make one unit.
     */
    private static boolean handles(JavaClass handler, JavaClass annotation) {
        return annotation.isAnnotation() && annotation.getAnnotations().stream()
                .flatMap(meta -> meta.getProperties().values().stream())
                .flatMap(value -> value instanceof Object[] values ? Arrays.stream(values) : Stream.of(value))
                .anyMatch(handler::equals);
    }

    /**
     * The entities the classes hold the inverse side of an association with: a JPA {@code @OneToMany},
     * {@code @OneToOne} or {@code @ManyToMany} field with {@code mappedBy}, as {@code Owner.pets} is for
     * {@code Pet.owner}. The two sides are one association, which the owning side maps.
     */
    private static Set<JavaClass> inverseSides(List<JavaClass> members) {
        return members.stream()
                .flatMap(member -> member.getFields().stream())
                .filter(field -> field.getAnnotations().stream().anyMatch(ClassCycleCheck::isInverseSide))
                .flatMap(field -> field.getAllInvolvedRawTypes().stream())
                .map(ClassCycleCheck::topLevelOf)
                .collect(Collectors.toSet());
    }

    private static boolean isInverseSide(JavaAnnotation<?> annotation) {
        String name = annotation.getRawType().getName();
        return INVERSE_ASSOCIATIONS.contains(name.substring(name.lastIndexOf('.') + 1))
                && PERSISTENCE.contains(annotation.getRawType().getPackageName())
                && annotation.get("mappedBy").filter(mappedBy -> !"".equals(mappedBy)).isPresent();
    }

    private static JavaClass topLevelOf(JavaClass type) {
        JavaClass current = type;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }

    /** The nodes in the order a depth-first search finishes them, without recursion. */
    private static List<Integer> finishOrder(List<List<Integer>> successors) {
        int size = successors.size();
        boolean[] seen = new boolean[size];
        int[] nextChild = new int[size];
        Deque<Integer> stack = new ArrayDeque<>();
        List<Integer> order = new ArrayList<>(size);
        for (int start = 0; start < size; start++) {
            if (seen[start]) {
                continue;
            }
            seen[start] = true;
            stack.push(start);
            while (!stack.isEmpty()) {
                int node = stack.peek();
                List<Integer> children = successors.get(node);
                if (nextChild[node] < children.size()) {
                    int child = children.get(nextChild[node]);
                    nextChild[node]++;
                    if (!seen[child]) {
                        seen[child] = true;
                        stack.push(child);
                    }
                } else {
                    order.add(stack.pop());
                }
            }
        }
        return order;
    }

    /** Each node's strongly connected component, found on the reversed graph in reverse finish order (Kosaraju). */
    private static int[] components(List<List<Integer>> successors, List<Integer> finishOrder) {
        int size = successors.size();
        List<List<Integer>> predecessors = IntStream.range(0, size).<List<Integer>>mapToObj(index -> new ArrayList<>())
                .toList();
        for (int node = 0; node < size; node++) {
            for (int child : successors.get(node)) {
                predecessors.get(child).add(node);
            }
        }
        int[] component = new int[size];
        Arrays.fill(component, -1);
        int count = 0;
        for (int position = finishOrder.size() - 1; position >= 0; position--) {
            int root = finishOrder.get(position);
            if (component[root] < 0) {
                mark(component, root, count, predecessors);
                count++;
            }
        }
        return component;
    }

    /** Puts every node that reaches the root, and isn't in a component yet, into the root's component. */
    private static void mark(int[] component, int root, int id, List<List<Integer>> predecessors) {
        Deque<Integer> stack = new ArrayDeque<>();
        component[root] = id;
        stack.push(root);
        while (!stack.isEmpty()) {
            for (int parent : predecessors.get(stack.pop())) {
                if (component[parent] < 0) {
                    component[parent] = id;
                    stack.push(parent);
                }
            }
        }
    }

    private static Violation violation(ProjectClasses classes, List<JavaClass> members) {
        List<String> names = members.stream().map(ProjectClasses::nameOf).sorted().toList();
        JavaClass first = members.stream().min(Comparator.comparing(ProjectClasses::nameOf)).orElseThrow();
        List<String> listed = names.size() > LISTED
                ? Stream.concat(names.stream().limit(LISTED), Stream.of((names.size() - LISTED) + " more")).toList()
                : names;
        String joined = listed.size() == 2 ? listed.get(0) + " and " + listed.get(1)
                : String.join(", ", listed.subList(0, listed.size() - 1)) + " and " + listed.get(listed.size() - 1);
        return classes.violation(RULE, first, 0, joined + " depend on each other in a cycle",
                "break the cycle: move what they share into a class of its own, or let one side depend on an interface it owns");
    }
}
