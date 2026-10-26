package io.github.yoavgeva.designguard.internal;

import java.util.stream.Stream;

/** Finds one kind of dispatch site in one source file. */
@FunctionalInterface
interface SiteFinder {

    /**
     * Finds dispatch sites.
     *
     * @param file the parsed file
     * @return the sites, in any order
     */
    Stream<DispatchSite> find(ParsedFile file);
}
