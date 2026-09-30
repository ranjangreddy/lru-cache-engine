package io.github.ranjangreddy.lru;

/**
 * Callback invoked after an entry leaves the cache.
 *
 * <p>Listeners run on the thread that caused the removal, <em>after</em> the cache's internal lock has
 * been released, so they may safely call back into the cache. Exceptions thrown by a listener are
 * logged and swallowed; they never corrupt the cache or fail the triggering operation.
 */
@FunctionalInterface
public interface RemovalListener<K, V> {
    void onRemoval(K key, V value, RemovalCause cause);
}
