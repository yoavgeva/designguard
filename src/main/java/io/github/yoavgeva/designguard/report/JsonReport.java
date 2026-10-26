package io.github.yoavgeva.designguard.report;

import static io.github.yoavgeva.designguard.report.Json.field;
import static io.github.yoavgeva.designguard.report.Json.text;

import io.github.yoavgeva.designguard.model.Violation;
import java.util.List;
import java.util.Locale;

/**
 * Renders violations as JSON, for tools and agents: an object whose {@code violations} array holds one object per
 * violation, with its rule, severity, file, line (0 when unknown), message, fix and identity.
 */
public final class JsonReport {

    /**
     * Renders the violations.
     *
     * @param violations the violations, in order
     * @return the JSON document, ending with a newline
     */
    public String render(List<Violation> violations) {
        return Json.object(field("violations", Json.array(violations.stream().map(JsonReport::violation).toList())))
                .write(0) + "\n";
    }

    private static Json violation(Violation violation) {
        return Json.object(
                field("rule", text(violation.rule())),
                field("severity", text(violation.severity().name().toLowerCase(Locale.ROOT))),
                field("file", text(violation.file())),
                field("line", Json.whole(violation.line())),
                field("message", text(violation.message())),
                field("fix", text(violation.fix())),
                field("identity", text(violation.identity())));
    }
}
