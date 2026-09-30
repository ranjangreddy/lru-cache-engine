package io.github.ranjangreddy.lru;

import java.util.concurrent.atomic.LongAdder;

/**
 * Contention-friendly counters. {@link LongAdder} keeps per-thread cells, so recording a hit never
 * becomes a new point of contention even when many threads read at once.
 */
final class StatsRecorder {
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder expirations = new LongAdder();
    private final LongAdder loadSuccesses = new LongAdder();
    private final LongAdder loadFailures = new LongAdder();

    void recordHit() { hits.increment(); }
    void recordMiss() { misses.increment(); }
    void recordEviction() { evictions.increment(); }
    void recordExpiration() { expirations.increment(); }
    void recordLoadSuccess() { loadSuccesses.increment(); }
    void recordLoadFailure() { loadFailures.increment(); }

    CacheStats snapshot() {
        return new CacheStats(hits.sum(), misses.sum(), evictions.sum(),
                expirations.sum(), loadSuccesses.sum(), loadFailures.sum());
    }
}
