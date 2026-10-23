package io.github.yoavgeva.designguard.view;

/**
 * A whole JSON number.
 *
 * @param value the number
 */
record JsonWhole(int value) implements Json {

    @Override
    public String write(int depth) {
        return Integer.toString(value);
    }
}
