package io.github.yoavgeva.designguard.view;

import static io.github.yoavgeva.designguard.view.Json.field;
import static io.github.yoavgeva.designguard.view.Json.text;

import io.github.yoavgeva.designguard.model.Violation;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Renders violations as a SARIF 2.1.0 log, which GitHub code scanning and other tools show as annotations: one run of
 * designguard, its rules, and one result per violation. Each result carries the violation's file, rule and identity
 * as a fingerprint, so tools keep tracking it as the code around it moves. Relative paths stay relative, as code
 * scanning expects paths from the repository root; absolute ones become {@code file} URIs.
 */
public final class SarifReport {

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("[A-Za-z]:/.*");

    /**
     * Renders the violations.
     *
     * @param violations the violations, in order
     * @return the SARIF document, ending with a newline
     */
    public String render(List<Violation> violations) {
        List<String> rules = violations.stream().map(Violation::rule).distinct().sorted().toList();
        Json driver = Json.object(
                field("name", text("designguard")),
                field("informationUri", text("https://github.com/yoavgeva/designguard")),
                field("rules", Json.array(rules.stream().map(rule -> Json.object(field("id", text(rule)))).toList())));
        Json run = Json.object(
                field("tool", Json.object(field("driver", driver))),
                field("results", Json.array(violations.stream().map(violation -> result(violation, rules)).toList())));
        return Json.object(
                field("$schema", text("https://json.schemastore.org/sarif-2.1.0.json")),
                field("version", text("2.1.0")),
                field("runs", Json.array(List.of(run))))
                .write(0) + "\n";
    }

    private static Json result(Violation violation, List<String> rules) {
        return Json.object(
                field("ruleId", text(violation.rule())),
                field("ruleIndex", Json.whole(rules.indexOf(violation.rule()))),
                field("level", text(violation.severity().name().toLowerCase(Locale.ROOT))),
                field("message", Json.object(field("text", text(violation.message() + " -> fix: " + violation.fix())))),
                field("locations", Json.array(List.of(Json.object(field("physicalLocation", location(violation)))))),
                field("partialFingerprints", Json.object(field("designguard/v1",
                        text(violation.file() + ": [" + violation.rule() + "] " + violation.identity())))));
    }

    private static Json location(Violation violation) {
        List<JsonFields.Field> fields = new ArrayList<>();
        fields.add(field("artifactLocation", Json.object(field("uri", text(uri(violation.file()))))));
        if (violation.line() > 0) {
            fields.add(field("region", Json.object(field("startLine", Json.whole(violation.line())))));
        }
        return Json.object(fields.toArray(JsonFields.Field[]::new));
    }

    /** The file as a URI: relative paths stay relative, absolute ones, on Unix or Windows, become {@code file} URIs. */
    private static String uri(String file) {
        String slashed = file.replace('\\', '/');
        try {
            if (slashed.startsWith("/")) {
                return new URI("file", "", slashed, null).toASCIIString();
            }
            if (WINDOWS_DRIVE.matcher(slashed).matches()) {
                return new URI("file", "", "/" + slashed, null).toASCIIString();
            }
            return new URI(null, null, slashed, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("cannot write " + file + " as a URI", e);
        }
    }
}
