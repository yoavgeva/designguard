package io.github.yoavgeva.designguard.service;

import java.util.List;

/** Finds every place in the sources that branches on the subtype or enum constant of a project type. */
final class DispatchSites {

    private final JavaSources sources;

    DispatchSites(JavaSources sources) {
        this.sources = sources;
    }

    List<DispatchSite> find() {
        List<SiteFinder> finders = List.of(
                new SwitchSites(sources), new InstanceofChainSites(sources), new EnumComparisonSites(sources));
        return sources.files().stream()
                .flatMap(file -> finders.stream().flatMap(finder -> finder.find(file)))
                .toList();
    }
}
