package io.github.ranjangreddy.lru;

/** Immutable snapshot of cache counters. */
public final class CacheStats {
    private final long hitCount;
    private final long missCount;
    private final long evictionCount;
    private final long expirationCount;
    private final long loadSuccessCount;
    private final long loadFailureCount;

    public CacheStats(long hitCount, long missCount, long evictionCount,
                      long expirationCount, long loadSuccessCount, long loadFailureCount) {
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.evictionCount = evictionCount;
        this.expirationCount = expirationCount;
        this.loadSuccessCount = loadSuccessCount;
        this.loadFailureCount = loadFailureCount;
    }

    public long hitCount() { return hitCount; }
    public long missCount() { return missCount; }
    /** Entries removed because the cache was full. */
    public long evictionCount() { return evictionCount; }
    /** Entries removed because their time-to-live elapsed. */
    public long expirationCount() { return expirationCount; }
    public long loadSuccessCount() { return loadSuccessCount; }
    public long loadFailureCount() { return loadFailureCount; }

    public long requestCount() {
        return hitCount + missCount;
    }

    /** Fraction of requests that were hits, or 1.0 if there have been no requests. */
    public double hitRate() {
        long requests = requestCount();
        return requests == 0 ? 1.0 : (double) hitCount / requests;
    }

    /** Sums two snapshots; used to aggregate the shards of a striped cache. */
    public CacheStats plus(CacheStats other) {
        return new CacheStats(
                hitCount + other.hitCount,
                missCount + other.missCount,
                evictionCount + other.evictionCount,
                expirationCount + other.expirationCount,
                loadSuccessCount + other.loadSuccessCount,
                loadFailureCount + other.loadFailureCount);
    }

    @Override
    public String toString() {
        return String.format(
                "CacheStats{hits=%d, misses=%d, hitRate=%.3f, evictions=%d, expirations=%d, loads=%d, loadFailures=%d}",
                hitCount, missCount, hitRate(), evictionCount, expirationCount, loadSuccessCount, loadFailureCount);
    }
}
