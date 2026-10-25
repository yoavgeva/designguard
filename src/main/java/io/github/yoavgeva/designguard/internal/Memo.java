package io.github.yoavgeva.designguard.internal;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Computes a value on first use and returns the same value afterwards. */
final class Memo<T> {

    private final Supplier<T> factory;
    private final AtomicReference<@Nullable T> value = new AtomicReference<>();

    Memo(Supplier<T> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    T get() {
        @Nullable T current = value.get();
        if (current != null) {
            return current;
        }
        value.compareAndSet(null, Objects.requireNonNull(factory.get(), "factory result"));
        return Objects.requireNonNull(value.get());
    }
}
