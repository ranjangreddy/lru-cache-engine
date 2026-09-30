package io.github.ranjangreddy.lru;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    /**
     * Hammers the cache from many threads with a mixed workload and checks the invariants that a
     * data race would break: size never exceeds capacity, every value read belongs to its key, and
     * the removal listener sees exactly as many removals as there were insertions minus survivors.
     */
    @ParameterizedTest(name = "concurrencyLevel={0}")
    @ValueSource(ints = {1, 16})
    void invariantsHoldUnderContention(int concurrencyLevel) throws Exception {
        int capacity = 256;
        AtomicInteger removals = new AtomicInteger();
        AtomicInteger inserts = new AtomicInteger();
        Cache<Integer, Integer> cache = CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(capacity)
                .concurrencyLevel(concurrencyLevel)
                .removalListener((k, v, cause) -> removals.incrementAndGet())
                .build();

        int threads = 16;
        int opsPerThread = 50_000;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rnd.nextInt(capacity * 4);
                        int op = rnd.nextInt(10);
                        if (op < 6) {
                            Integer v = cache.get(key);
                            if (v != null && v != key * 31) {
                                throw new AssertionError("key " + key + " mapped to foreign value " + v);
                            }
                        } else if (op < 9) {
                            cache.put(key, key * 31);
                            inserts.incrementAndGet();
                        } else {
                            cache.remove(key);
                        }
                        if (cache.size() > capacity) {
                            throw new AssertionError("size exceeded capacity: " + cache.size());
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(cache.size() <= capacity);
        // Every put either still lives in the cache or was reported as removed exactly once.
        assertEquals(inserts.get(), cache.size() + removals.get());
    }
}
