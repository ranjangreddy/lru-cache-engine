package io.github.ranjangreddy.lru;

/** Why an entry left the cache. */
public enum RemovalCause {
    /** Evicted because the cache was full and this was the least recently used entry. */
    SIZE,
    /** Its time-to-live elapsed. */
    EXPIRED,
    /** Its value was overwritten by a {@code put} for the same key. */
    REPLACED,
    /** Removed by {@code remove} or {@code clear}. */
    EXPLICIT;

    /** True if the entry was removed automatically rather than by the caller. */
    public boolean wasEvicted() {
        return this == SIZE || this == EXPIRED;
    }
}
