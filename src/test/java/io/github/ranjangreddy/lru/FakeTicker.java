package io.github.ranjangreddy.lru;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** A manually advanced clock so expiry tests never sleep. */
final class FakeTicker implements Ticker {
    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long nanoTime() {
        return nanos.get();
    }

    void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }
}
