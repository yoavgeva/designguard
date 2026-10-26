package io.github.yoavgeva.designguard.report;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A JSON object, one field per line.
 *
 * @param fields the fields, in order
 */
record JsonFields(List<JsonFields.Field> fields) implements Json {

    JsonFields {
        fields = List.copyOf(fields);
    }

    @Override
    public String write(int depth) {
        return fields.stream()
                .map(field -> Json.indent(depth + 1) + new JsonText(field.name()).write(depth + 1) + ": "
                        + field.value().write(depth + 1))
                .collect(Collectors.joining(",\n", "{\n", "\n" + Json.indent(depth) + "}"));
    }

    /**
     * A field of a JSON object.
     *
     * @param name  its name
     * @param value its value
     */
    record Field(String name, Json value) {
    }
}
