package io.github.ranjangreddy.lru;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LruCacheTest {

    @Test
    void evictsLeastRecentlyUsedWhenFull() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        cache.put("d", 4);

        assertNull(cache.get("a"));
        assertEquals(List.of("d", "c", "b"), cache.keysByRecency());
    }

    @Test
    void getPromotesEntrySoItSurvivesEviction() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        cache.get("a"); // a is now most recent; b is least recent
        cache.put("d", 4);

        assertEquals(1, cache.get("a"));
        assertNull(cache.get("b"));
    }

    @Test
    void putOnExistingKeyReplacesValueAndPromotes() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);

        cache.put("a", 10);
        cache.put("c", 3);

        assertEquals(10, cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals(2, cache.size());
    }

    @Test
    void capacityOfOneAlwaysHoldsTheLatestEntry() {
        LruCache<Integer, Integer> cache = new LruCache<>(1);
        for (int i = 0; i < 100; i++) {
            cache.put(i, i);
            assertEquals(1, cache.size());
            assertEquals(i, cache.get(i));
        }
    }

    @Test
    void containsKeyDoesNotChangeRecency() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);

        assertTrue(cache.containsKey("a"));
        cache.put("c", 3);

        assertFalse(cache.containsKey("a")); // a was still least recent
    }

    @Test
    void removeAndClear() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);

        assertEquals(1, cache.remove("a"));
        assertNull(cache.remove("a"));
        assertEquals(1, cache.size());

        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(List.of(), cache.keysByRecency());
        cache.put("z", 26); // still usable after clear
        assertEquals(26, cache.get("z"));
    }

    @Test
    void tracksHitsMissesAndEvictions() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.get("a");
        cache.get("a");
        cache.get("missing");
        cache.put("b", 2);
        cache.put("c", 3); // evicts a

        CacheStats stats = cache.stats();
        assertEquals(2, stats.hitCount());
        assertEquals(1, stats.missCount());
        assertEquals(1, stats.evictionCount());
        assertEquals(2.0 / 3, stats.hitRate(), 1e-9);
    }

    @Test
    void rejectsNullsAndBadCapacity() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        assertThrows(NullPointerException.class, () -> cache.put(null, 1));
        assertThrows(NullPointerException.class, () -> cache.put("a", null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(IllegalArgumentException.class, () -> new LruCache<String, Integer>(0));
        assertThrows(IllegalStateException.class, () -> CacheBuilder.newBuilder().build());
    }
}
