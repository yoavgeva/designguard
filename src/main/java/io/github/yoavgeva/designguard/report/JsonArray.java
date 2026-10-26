package io.github.yoavgeva.designguard.report;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A JSON array, one item per line.
 *
 * @param items the items, in order
 */
record JsonArray(List<Json> items) implements Json {

    JsonArray {
        items = List.copyOf(items);
    }

    @Override
    public String write(int depth) {
        if (items.isEmpty()) {
            return "[]";
        }
        return items.stream().map(item -> Json.indent(depth + 1) + item.write(depth + 1))
                .collect(Collectors.joining(",\n", "[\n", "\n" + Json.indent(depth) + "]"));
    }
}
