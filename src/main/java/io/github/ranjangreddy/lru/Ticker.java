package io.github.ranjangreddy.lru;

/**
 * A source of monotonic time in nanoseconds. Injected so that expiry can be tested
 * deterministically without sleeping.
 */
@FunctionalInterface
public interface Ticker {
    long nanoTime();

    Ticker SYSTEM = System::nanoTime;
}
