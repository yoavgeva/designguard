package io.github.yoavgeva.designguard.view;

import java.util.List;

/** A JSON value, written with two-space indentation; objects keep their fields in order. */
sealed interface Json permits JsonText, JsonWhole, JsonArray, JsonFields {

    /**
     * Writes the value.
     *
     * @param depth how deeply it is nested, for the indentation of the lines after its first
     * @return the JSON text
     */
    String write(int depth);

    /**
     * A string.
     *
     * @param value the string
     * @return the value
     */
    static Json text(String value) {
        return new JsonText(value);
    }

    /**
     * A whole number.
     *
     * @param value the number
     * @return the value
     */
    static Json whole(int value) {
        return new JsonWhole(value);
    }

    /**
     * An array, one item per line.
     *
     * @param items the items, in order
     * @return the value
     */
    static Json array(List<? extends Json> items) {
        return new JsonArray(List.copyOf(items));
    }

    /**
     * An object, one field per line.
     *
     * @param fields the fields, in order
     * @return the value
     */
    static Json object(JsonFields.Field... fields) {
        return new JsonFields(List.of(fields));
    }

    /**
     * A field of an object.
     *
     * @param name  its name
     * @param value its value
     * @return the field
     */
    static JsonFields.Field field(String name, Json value) {
        return new JsonFields.Field(name, value);
    }

    /**
     * The indentation of a line at a depth.
     *
     * @param depth how deeply the line is nested
     * @return two spaces per level
     */
    static String indent(int depth) {
        return "  ".repeat(depth);
    }
}
