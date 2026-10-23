package io.github.yoavgeva.designguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The call graph stays fast on large projects, however deep their call chains. */
class CallGraphTest {

    private static final int DEPTH = 100_000;

    @Test
    void spreadsUpADeepChainOfCallsInLinearTime() {
        Map<String, Set<String>> callees = IntStream.range(0, DEPTH).boxed()
                .collect(Collectors.toMap(index -> "m" + index, index -> Set.of("m" + (index + 1))));

        Set<String> reached = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> CallGraph.spread(callees, Set.of("m" + DEPTH)));

        assertThat(reached).hasSize(DEPTH + 1).contains("m0");
    }
}
