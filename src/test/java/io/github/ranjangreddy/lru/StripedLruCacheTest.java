package io.github.ranjangreddy.lru;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripedLruCacheTest {

    @Test
    void builderRoundsConcurrencyLevelUpToPowerOfTwo() {
        Cache<Integer, Integer> cache = CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(100)
                .concurrencyLevel(10)
                .build();

        StripedLruCache<?, ?> striped = assertInstanceOf(StripedLruCache.class, cache);
        assertEquals(16, striped.segmentCount());
        assertEquals(100, striped.capacity());
    }

    @Test
    void neverExceedsTotalCapacity() {
        Cache<Integer, Integer> cache = CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(100)
                .concurrencyLevel(8)
                .build();
        for (int i = 0; i < 10_000; i++) {
            cache.put(i, i);
        }
        assertTrue(cache.size() <= 100);
        assertTrue(cache.size() >= 90, "segments should be close to full, was " + cache.size());
        assertEquals(10_000 - cache.size(), cache.stats().evictionCount());
    }

    @Test
    void recentlyWrittenKeysSurvive() {
        Cache<Integer, Integer> cache = CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(1_000)
                .concurrencyLevel(16)
                .build();
        for (int i = 0; i < 5_000; i++) {
            cache.put(i, i);
        }
        // The newest 20 keys are the most recent in whichever segment they landed in.
        for (int i = 4_980; i < 5_000; i++) {
            assertEquals(i, cache.get(i));
        }
    }

    @Test
    void aggregatesStatsAcrossSegments() {
        Cache<Integer, Integer> cache = CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(64)
                .concurrencyLevel(4)
                .build();
        for (int i = 0; i < 10; i++) {
            cache.put(i, i);
            cache.get(i);
            cache.get(i + 1_000);
        }
        assertEquals(10, cache.stats().hitCount());
        assertEquals(10, cache.stats().missCount());
    }

    @Test
    void rejectsCapacitySmallerThanSegmentCount() {
        assertThrows(IllegalArgumentException.class, () -> CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(4)
                .concurrencyLevel(8)
                .build());
    }
}
