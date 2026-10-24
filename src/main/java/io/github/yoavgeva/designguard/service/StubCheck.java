package io.github.yoavgeva.designguard.service;

import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;

/** Finds unfinished code: TODO/FIXME comments, UnsupportedOperationException placeholders and empty methods. */
public final class StubCheck {

    static final String RULE = "no-stubs";

    /**
     * Finds stubs in the sources the filter selects.
     *
     * @param sources the parsed sources
     * @param filter  which classes to check
     * @return violations ordered by file and line
     */
    public List<Violation> check(JavaSources sources, ClassFilter filter) {
        OverriddenMethods overridden = OverriddenMethods.in(sources);
        List<SourceFinder> finders = List.of(new TodoComments(RULE), new PlaceholderThrows(RULE, overridden),
                new EmptyMethods(RULE, overridden));
        return sources.files().stream()
                .flatMap(file -> finders.stream().flatMap(finder -> finder.find(file, filter)))
                .sorted(Scopes.BY_LOCATION)
                .toList();
    }
}
