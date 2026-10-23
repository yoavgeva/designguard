package io.github.yoavgeva.designguard.service;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.stream.Stream;

/** Finds one kind of violation in one source file. */
@FunctionalInterface
interface SourceFinder {

    /**
     * Finds violations in the parts of the file the filter selects.
     *
     * @param file   the parsed file
     * @param filter which classes to check
     * @return the violations, in any order
     */
    Stream<Violation> find(ParsedFile file, ClassFilter filter);
}
