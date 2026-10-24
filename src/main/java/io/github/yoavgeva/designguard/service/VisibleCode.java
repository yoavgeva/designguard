package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;

/**
 * Whether designguard sees everything a class does: it extends no library class, holds no library object but data,
 * and calls no library object of its own, a call on a parameter running what the caller passed. A class that works
 * through a library, as a search engine over Lucene's directory or a plugin manager extending pf4j's does, may do I/O
 * designguard can't see, so it can't vouch that the class is a value. Libraries are what is neither the project's nor
 * the JDK's, whose effects designguard knows.
 */
final class VisibleCode {

    private final ProjectClasses classes;

    VisibleCode(ProjectClasses classes) {
        this.classes = classes;
    }

    /** Whether designguard sees everything the class does. */
    boolean isSeenThrough(JavaClass type) {
        return type.getAllRawSuperclasses().stream().noneMatch(this::isLibrary)
                && type.getAllFields().stream()
                        .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
                        .flatMap(field -> field.getAllInvolvedRawTypes().stream())
                        .allMatch(held -> !isLibrary(held) || isData(held))
                && type.getCodeUnits().stream()
                        .flatMap(unit -> unit.getMethodCallsFromSelf().stream())
                        .noneMatch(this::callsLibraryObject);
    }

    private boolean callsLibraryObject(JavaMethodCall call) {
        JavaClass owner = call.getTargetOwner();
        boolean isStatic = call.getTarget().resolveMember()
                .filter(method -> method.getModifiers().contains(JavaModifier.STATIC))
                .isPresent();
        return isLibrary(owner) && !isData(owner) && !isStatic
                && !classes.bytecode().isCall(Bytecode.CallKind.ON_PARAMETER, call);
    }

    private boolean isLibrary(JavaClass type) {
        JavaClass base = type.isArray() ? type.getBaseComponentType() : type;
        String name = base.getName();
        return !base.isPrimitive() && !classes.isProject(base) && !name.startsWith("java.") && !name.startsWith("javax.")
                && !name.startsWith("jdk.");
    }

    private static boolean isData(JavaClass type) {
        return type.isEnum() || type.isRecord() || type.isAnnotation();
    }
}
