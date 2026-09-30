package io.github.ranjangreddy.lru;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Lock striping: the key space is split across N independent {@link LruCache} segments, each with its
 * own lock, so threads touching different keys rarely contend.
 *
 * <p>The trade-off is that recency is tracked per segment, so eviction is an approximation of global
 * LRU: the entry evicted is the least recently used <em>in its segment</em>. With a reasonable number
 * of keys per segment and a well-distributed hash this is close to true LRU in practice, which is the
 * same compromise Memcached's slab LRUs and Java's old segmented {@code ConcurrentHashMap} made.
 */
public final class StripedLruCache<K, V> implements Cache<K, V> {

    private final LruCache<K, V>[] segments;
    private final int segmentShift;
    private final int capacity;
    private volatile ScheduledExecutorService sweeper;

    @SuppressWarnings("unchecked")
    StripedLruCache(int capacity, int segmentCount, long defaultTtlNanos, Ticker ticker,
                    RemovalListener<K, V> listener) {
        if (Integer.bitCount(segmentCount) != 1) {
            throw new IllegalArgumentException("segmentCount must be a power of two: " + segmentCount);
        }
        if (capacity < segmentCount) {
            throw new IllegalArgumentException(
                    "capacity (" + capacity + ") must be at least the number of segments (" + segmentCount + ")");
        }
        this.capacity = capacity;
        this.segmentShift = 32 - Integer.numberOfTrailingZeros(segmentCount);
        this.segments = (LruCache<K, V>[]) new LruCache<?, ?>[segmentCount];
        // Spread the remainder so the segment capacities sum to exactly `capacity`.
        int base = capacity / segmentCount;
        int remainder = capacity % segmentCount;
        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new LruCache<>(base + (i < remainder ? 1 : 0), defaultTtlNanos, ticker, listener);
        }
    }

    /**
     * Picks a segment from the <em>top</em> bits of a Fibonacci-hashed key.
     *
     * <p>Using the low bits ({@code hash & mask}) looks natural but is a trap: each segment's
     * {@link java.util.HashMap} also indexes buckets by the low bits, so every key in segment 3 would
     * share the same low 4 bits and pile into 1/16th of that segment's buckets. Benchmarked, that
     * made the striped cache ~2.5x slower than the single-lock one. Multiplying by 2^32/phi spreads
     * every input bit into the high bits, which the HashMaps never look at.
     */
    private LruCache<K, V> segmentFor(Object key) {
        if (segmentShift == 32) {
            return segments[0];
        }
        return segments[(key.hashCode() * 0x9E3779B9) >>> segmentShift];
    }

    @Override
    public V get(K key) {
        return segmentFor(requireKey(key)).get(key);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        return segmentFor(requireKey(key)).get(key, loader);
    }

    @Override
    public void put(K key, V value) {
        segmentFor(requireKey(key)).put(key, value);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        segmentFor(requireKey(key)).put(key, value, ttl);
    }

    @Override
    public V remove(K key) {
        return segmentFor(requireKey(key)).remove(key);
    }

    @Override
    public boolean containsKey(K key) {
        return segmentFor(requireKey(key)).containsKey(key);
    }

    @Override
    public int size() {
        int total = 0;
        for (LruCache<K, V> s : segments) {
            total += s.size();
        }
        return total;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    public int segmentCount() {
        return segments.length;
    }

    @Override
    public void clear() {
        for (LruCache<K, V> s : segments) {
            s.clear();
        }
    }

    @Override
    public int cleanUp() {
        int removed = 0;
        for (LruCache<K, V> s : segments) {
            removed += s.cleanUp();
        }
        return removed;
    }

    @Override
    public CacheStats stats() {
        CacheStats total = new CacheStats(0, 0, 0, 0, 0, 0);
        for (LruCache<K, V> s : segments) {
            total = total.plus(s.stats());
        }
        return total;
    }

    void startSweeper(ScheduledExecutorService executor, long intervalNanos) {
        this.sweeper = executor;
        executor.scheduleWithFixedDelay(() -> {
            for (LruCache<K, V> s : segments) {
                try {
                    s.cleanUp();
                } catch (RuntimeException ignored) {
                    // keep sweeping the remaining segments; LruCache already logs listener failures
                }
            }
        }, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        ScheduledExecutorService s = sweeper;
        if (s != null) {
            s.shutdownNow();
        }
    }

    private static <K> K requireKey(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        return key;
    }
}
