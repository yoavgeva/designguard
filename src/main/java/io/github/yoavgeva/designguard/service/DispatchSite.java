package io.github.yoavgeva.designguard.service;

import com.github.javaparser.ast.Node;

/**
 * A place where code branches on the kind of a project type: its subtype or its enum constant.
 *
 * @param type              the project type being branched on
 * @param kind              how the code branches
 * @param file              the file containing the branch
 * @param node              the switch, if statement or comparison
 * @param mustBeExhaustive  whether the compiler requires the branch to cover every case (switch expressions and
 *                          pattern switches)
 * @param hasDefault        whether a switch has a default branch
 * @param insideOwnType     whether the branch is inside the declaration of the type itself
 */
record DispatchSite(
        DeclaredType type, Kind kind, ParsedFile file, Node node,
        boolean mustBeExhaustive, boolean hasDefault, boolean insideOwnType) {

    /** How the code branches. */
    enum Kind {
        /** A switch with type or record patterns. */
        TYPE_SWITCH,
        /** A switch over enum constants. */
        ENUM_SWITCH,
        /** {@code if (x instanceof A) ... else if (x instanceof B)}. */
        INSTANCEOF_CHAIN,
        /** {@code if (x == Color.RED)} or {@code Color.RED.equals(x)}. */
        ENUM_COMPARISON
    }

    /** Whether the compiler would report a new subtype or constant this branch doesn't handle. */
    boolean checkedByCompiler() {
        return mustBeExhaustive && !hasDefault && (type.isSealed() || type.isEnum());
    }

    String location() {
        String path = file.path();
        return path.substring(path.lastIndexOf('/') + 1) + ":" + Scopes.line(node);
    }
}
