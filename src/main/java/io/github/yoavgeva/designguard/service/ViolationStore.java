package io.github.yoavgeva.designguard.service;

import io.github.yoavgeva.designguard.model.DesignGuardException;
import io.github.yoavgeva.designguard.model.FreezeResult;
import io.github.yoavgeva.designguard.model.Violation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The violations a frozen rule accepts, so the rule can be adopted on code that already breaks it. A violation is
 * recorded by its file, rule and message, not its line, so editing elsewhere in the file keeps it frozen. Each recorded
 * violation accepts one occurrence, and fixed ones drop out of the record, so it only shrinks.
 */
public abstract sealed class ViolationStore {

    /**
     * A store in a text file, one violation per line. Files are named relative to the working directory, so the record
     * is the same on every machine.
     *
     * @param file the file; created on the first run
     * @return the store
     */
    static ViolationStore inFile(Path file) {
        return new InFile(file);
    }

    /**
     * Compares the violations with the record and updates it. Without a record, it records them all. With one, it
     * drops the recorded violations that are gone.
     *
     * @param violations a rule's current violations
     * @return what the comparison found
     */
    public final FreezeResult freeze(List<Violation> violations) {
        Optional<List<String>> recorded = read();
        List<String> keys = violations.stream().map(ViolationStore::key).toList();
        if (recorded.isEmpty()) {
            write(sorted(keys));
            return new FreezeResult(location(), FreezeResult.Outcome.RECORDED, keys.size(), violations);
        }
        Map<String, Long> accepted = recorded.get().stream()
                .collect(Collectors.groupingBy(key -> key, Collectors.counting()));
        Map<String, List<Violation>> byKey = violations.stream()
                .collect(Collectors.groupingBy(ViolationStore::key, LinkedHashMap::new, Collectors.toList()));
        List<String> kept = byKey.entrySet().stream()
                .flatMap(entry -> Collections.nCopies(Math.toIntExact(Math.min(entry.getValue().size(),
                        accepted.getOrDefault(entry.getKey(), 0L))), entry.getKey()).stream())
                .toList();
        List<Violation> added = byKey.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().skip(accepted.getOrDefault(entry.getKey(), 0L)))
                .sorted(Comparator.comparing(Violation::file).thenComparingInt(Violation::line))
                .toList();
        if (kept.size() < recorded.get().size()) {
            write(sorted(kept));
        }
        return new FreezeResult(location(), FreezeResult.Outcome.COMPARED, kept.size(), added);
    }

    abstract Optional<List<String>> read();

    abstract void write(List<String> keys);

    abstract String location();

    /** One line per violation: {@code file: [rule] message}, the file relative to the working directory. */
    private static String key(Violation violation) {
        Path file = Path.of(violation.file());
        Path workingDirectory = Path.of("").toAbsolutePath();
        if (file.isAbsolute() && file.startsWith(workingDirectory)) {
            file = workingDirectory.relativize(file);
        }
        String name = file.isAbsolute() ? file.toString()
                : StreamSupport.stream(file.spliterator(), false).map(Path::toString).collect(Collectors.joining("/"));
        String line = name + ": [" + violation.rule() + "] " + violation.message();
        return line.replace("\r", "\\r").replace("\n", "\\n");
    }

    private static List<String> sorted(List<String> keys) {
        return keys.stream().sorted().toList();
    }

    /** A record in a text file; lines starting with {@code #} are comments. */
    private static final class InFile extends ViolationStore {

        private static final String HEADER = """
                # Design violations accepted by designguard's frozen rule %s: only violations not listed here fail it.
                # Fixed ones are removed automatically. To accept the current violations again, delete this file and run
                # the rule.
                """;

        private final Path file;

        InFile(Path file) {
            this.file = file;
        }

        @Override
        Optional<List<String>> read() {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                return Optional.of(lines.filter(line -> !line.isBlank() && !line.startsWith("#")).toList());
            } catch (IOException e) {
                throw new DesignGuardException("cannot read frozen violations from " + file, e);
            }
        }

        @Override
        void write(List<String> keys) {
            String name = file.getFileName().toString().replaceFirst("\\.txt$", "");
            String content = HEADER.formatted(name) + keys.stream().map(key -> key + "\n").collect(Collectors.joining());
            try {
                Path directory = file.toAbsolutePath().getParent();
                if (directory != null) {
                    Files.createDirectories(directory);
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new DesignGuardException("cannot record frozen violations in " + file, e);
            }
        }

        @Override
        String location() {
            return file.toString();
        }
    }
}
