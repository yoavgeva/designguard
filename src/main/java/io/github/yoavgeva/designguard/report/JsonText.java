package io.github.yoavgeva.designguard.report;

import java.util.stream.Collectors;

/**
 * A JSON string.
 *
 * @param value the string
 */
record JsonText(String value) implements Json {

    @Override
    public String write(int depth) {
        return value.chars().mapToObj(JsonText::escaped).collect(Collectors.joining("", "\"", "\""));
    }

    private static String escaped(int character) {
        return switch (character) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> character < ' ' ? "\\u%04x".formatted(character) : Character.toString(character);
        };
    }
}
