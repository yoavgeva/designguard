package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds classes that only carry data and should be records: every instance field is {@code private final}, and
 * every method is an accessor, {@code equals}, {@code hashCode}, {@code toString} or static. Classes that extend
 * another class, inner classes and persistence entities are skipped: they can't be records.
 */
public final class DataClassCheck {

    private static final String RULE = "data-classes-are-records";
    private static final Set<String> CANNOT_BE_RECORDS = Set.of("Entity", "Embeddable", "MappedSuperclass", "Document");

    /**
     * Finds data classes the filter selects.
     *
     * @param sources the parsed sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        return sources.files().stream()
                .flatMap(file -> file.unit().findAll(ClassOrInterfaceDeclaration.class).stream()
                        .filter(DataClassCheck::isDataClass)
                        .filter(type -> Scopes.selected(filter, file, type))
                        .map(type -> Scopes.violation(RULE, file, type.getName(),
                                type.getNameAsString() + " only carries data: make it a record",
                                "replace it with record " + type.getNameAsString() + "(" + components(type) + ")")))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }

    private static boolean isDataClass(ClassOrInterfaceDeclaration type) {
        List<FieldDeclaration> fields = instanceFields(type);
        Set<String> fieldNames = fields.stream()
                .flatMap(field -> field.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .collect(Collectors.toSet());
        return canBeRecord(type)
                && !fields.isEmpty()
                && fields.stream().allMatch(field -> field.isPrivate() && field.isFinal())
                && type.getMembers().stream().noneMatch(InitializerDeclaration.class::isInstance)
                && type.getMethods().stream().allMatch(method -> method.isStatic()
                        || isAccessor(method, fieldNames) || isObjectMethod(method));
    }

    private static boolean canBeRecord(ClassOrInterfaceDeclaration type) {
        return !type.isInterface()
                && !type.isAbstract()
                && type.getExtendedTypes().isEmpty()
                && (type.isTopLevelType() || type.isStatic())
                && CANNOT_BE_RECORDS.stream().noneMatch(annotation -> Scopes.hasAnnotation(type, annotation));
    }

    private static List<FieldDeclaration> instanceFields(ClassOrInterfaceDeclaration type) {
        return type.getFields().stream().filter(field -> !field.isStatic()).toList();
    }

    private static boolean isAccessor(MethodDeclaration method, Set<String> fieldNames) {
        List<Statement> statements = method.getBody().map(body -> List.copyOf(body.getStatements())).orElse(List.of());
        return method.getParameters().isEmpty()
                && statements.size() == 1
                && statements.getFirst() instanceof ReturnStmt returned
                && returned.getExpression().map(value -> isFieldRead(value, fieldNames)).orElse(false);
    }

    private static boolean isFieldRead(Expression value, Set<String> fieldNames) {
        return value instanceof NameExpr name && fieldNames.contains(name.getNameAsString())
                || value instanceof FieldAccessExpr access && access.getScope().isThisExpr()
                && fieldNames.contains(access.getNameAsString());
    }

    private static boolean isObjectMethod(MethodDeclaration method) {
        String name = method.getNameAsString();
        int parameters = method.getParameters().size();
        return "equals".equals(name) && parameters == 1
                || ("hashCode".equals(name) || "toString".equals(name)) && parameters == 0;
    }

    private static String components(ClassOrInterfaceDeclaration type) {
        return instanceFields(type).stream()
                .flatMap(field -> field.getVariables().stream())
                .map(variable -> variable.getType().asString() + " " + variable.getNameAsString())
                .collect(Collectors.joining(", "));
    }
}
