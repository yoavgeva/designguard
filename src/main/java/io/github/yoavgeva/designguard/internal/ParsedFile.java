package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;

/**
 * One parsed source file.
 *
 * @param path the file's path, as found under its source root
 * @param unit the parsed file
 */
record ParsedFile(String path, CompilationUnit unit) {

    String packageName() {
        return unit.getPackageDeclaration().map(PackageDeclaration::getNameAsString).orElse("");
    }
}
