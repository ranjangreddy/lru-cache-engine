package io.github.ranjangreddy.lru;

import java.time.Duration;
import java.util.function.Function;

/**
 * A bounded, thread-safe key/value cache.
 *
 * <p>Null keys and null values are not permitted. All operations are O(1) except
 * {@link #cleanUp()} and {@link #clear()}, which are O(n).
 *
 * @param <K> key type
 * @param <V> value type
 */
public interface Cache<K, V> extends AutoCloseable {

    /** Returns the cached value, or {@code null} if absent or expired. Marks the entry as most recently used. */
    V get(K key);

    /**
     * Returns the cached value, loading it with {@code loader} on a miss.
     *
     * <p>Loads are single-flight: if many threads miss on the same key at once, the loader runs
     * exactly once and every caller receives its result. If the loader returns {@code null} nothing
     * is cached and {@code null} is returned. If it throws, nothing is cached and the exception is
     * rethrown to every waiting caller (wrapped in {@link CacheLoadException} if checked).
     */
    V get(K key, Function<? super K, ? extends V> loader);

    /** Inserts or replaces a value using the cache's default time-to-live (if any). */
    void put(K key, V value);

    /**
     * Inserts or replaces a value with a per-entry time-to-live that overrides the default.
     * A zero or negative {@code ttl} means the entry never expires.
     */
    void put(K key, V value, Duration ttl);

    /** Removes an entry, returning its value, or {@code null} if absent or expired. */
    V remove(K key);

    /** Returns true if a live (non-expired) entry exists. Does not change recency. */
    boolean containsKey(K key);

    /**
     * Number of entries currently held, including expired entries that have not yet been
     * removed by a read, a write or {@link #cleanUp()}.
     */
    int size();

    /** Maximum number of entries the cache will hold before evicting. */
    int capacity();

    /** Removes all entries, notifying the removal listener with {@link RemovalCause#EXPLICIT}. */
    void clear();

    /** Eagerly removes every expired entry and returns how many were removed. */
    int cleanUp();

    /** A point-in-time snapshot of the cache's counters. */
    CacheStats stats();

    /** Stops the background expiry sweeper, if one was configured. The cache remains usable. */
    @Override
    void close();
}
