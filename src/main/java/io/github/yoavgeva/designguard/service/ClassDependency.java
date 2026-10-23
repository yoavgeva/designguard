package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;

/**
 * A class whose code uses another class.
 *
 * @param origin the class whose code uses the target
 * @param target the class used
 * @param line   the line of the use in the origin's source, or 0 if unknown
 */
record ClassDependency(JavaClass origin, JavaClass target, int line) {
}
