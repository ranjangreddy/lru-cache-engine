package io.github.ranjangreddy.lru;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ExpiryTest {

    private final FakeTicker ticker = new FakeTicker();

    private Cache<String, String> cacheWithTtl(Duration ttl) {
        return CacheBuilder.<String, String>newBuilder()
                .maximumSize(10)
                .expireAfterWrite(ttl)
                .ticker(ticker)
                .build();
    }

    @Test
    void entryExpiresAfterDefaultTtl() {
        Cache<String, String> cache = cacheWithTtl(Duration.ofSeconds(10));
        cache.put("k", "v");

        ticker.advance(Duration.ofSeconds(9));
        assertEquals("v", cache.get("k"));

        ticker.advance(Duration.ofSeconds(1));
        assertNull(cache.get("k"));
        assertEquals(0, cache.size()); // lazily removed on read
        assertEquals(1, cache.stats().expirationCount());
    }

    @Test
    void readingDoesNotExtendTtl() {
        Cache<String, String> cache = cacheWithTtl(Duration.ofSeconds(10));
        cache.put("k", "v");
        for (int i = 0; i < 9; i++) {
            ticker.advance(Duration.ofSeconds(1));
            cache.get("k");
        }
        ticker.advance(Duration.ofSeconds(1));
        assertNull(cache.get("k"));
    }

    @Test
    void rewritingResetsTtl() {
        Cache<String, String> cache = cacheWithTtl(Duration.ofSeconds(10));
        cache.put("k", "v1");
        ticker.advance(Duration.ofSeconds(8));
        cache.put("k", "v2");
        ticker.advance(Duration.ofSeconds(8));
        assertEquals("v2", cache.get("k"));
    }

    @Test
    void perEntryTtlOverridesDefault() {
        Cache<String, String> cache = cacheWithTtl(Duration.ofMinutes(10));
        cache.put("short", "s", Duration.ofSeconds(1));
        cache.put("forever", "f", Duration.ZERO);
        cache.put("default", "d");

        ticker.advance(Duration.ofSeconds(2));
        assertNull(cache.get("short"));
        assertEquals("d", cache.get("default"));

        ticker.advance(Duration.ofDays(365));
        assertEquals("f", cache.get("forever"));
        assertNull(cache.get("default"));
    }

    @Test
    void cleanUpRemovesExpiredEntriesWithoutReads() {
        List<RemovalCause> causes = new ArrayList<>();
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .maximumSize(10)
                .ticker(ticker)
                .removalListener((k, v, cause) -> causes.add(cause))
                .build();
        cache.put("a", "1", Duration.ofSeconds(1));
        cache.put("b", "2", Duration.ofSeconds(1));
        cache.put("c", "3");

        ticker.advance(Duration.ofSeconds(5));

        assertEquals(3, cache.size()); // not yet swept
        assertEquals(2, cache.cleanUp());
        assertEquals(1, cache.size());
        assertEquals(List.of(RemovalCause.EXPIRED, RemovalCause.EXPIRED), causes);
    }

    @Test
    void expiredTailIsReportedAsExpiredNotEvicted() {
        List<RemovalCause> causes = new ArrayList<>();
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .maximumSize(2)
                .ticker(ticker)
                .removalListener((k, v, cause) -> causes.add(cause))
                .build();
        cache.put("old", "x", Duration.ofSeconds(1));
        cache.put("b", "y");
        ticker.advance(Duration.ofSeconds(2));

        cache.put("c", "z"); // pushes "old" off the tail, but it had already expired

        assertEquals(List.of(RemovalCause.EXPIRED), causes);
        assertEquals(0, cache.stats().evictionCount());
    }

    @Test
    void backgroundSweeperRemovesExpiredEntries() {
        try (Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .maximumSize(10)
                .expireAfterWrite(Duration.ofMillis(20))
                .expirySweepInterval(Duration.ofMillis(10))
                .build()) {
            cache.put("a", "1");
            cache.put("b", "2");
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (cache.size() > 0) {
                    Thread.onSpinWait();
                }
            });
        }
    }
}
