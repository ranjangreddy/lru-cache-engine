package io.github.ranjangreddy.lru;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderTest {

    @Test
    void loadsOnMissAndCachesResult() {
        Cache<Integer, String> cache = CacheBuilder.<Integer, String>newBuilder().maximumSize(10).build();
        AtomicInteger calls = new AtomicInteger();

        assertEquals("v7", cache.get(7, k -> { calls.incrementAndGet(); return "v" + k; }));
        assertEquals("v7", cache.get(7, k -> { calls.incrementAndGet(); return "other"; }));

        assertEquals(1, calls.get());
        assertEquals(1, cache.stats().loadSuccessCount());
    }

    @Test
    void nullFromLoaderIsNotCached() {
        Cache<Integer, String> cache = CacheBuilder.<Integer, String>newBuilder().maximumSize(10).build();
        assertNull(cache.get(1, k -> null));
        assertEquals(0, cache.size());
    }

    @Test
    void loaderFailureIsRethrownAndNotCached() {
        Cache<Integer, String> cache = CacheBuilder.<Integer, String>newBuilder().maximumSize(10).build();

        assertThrows(IllegalStateException.class, () -> cache.get(1, k -> {
            throw new IllegalStateException("db down");
        }));

        assertEquals(0, cache.size());
        assertEquals(1, cache.stats().loadFailureCount());
        assertEquals("ok", cache.get(1, k -> "ok")); // a later load can still succeed
    }

    @Test
    void concurrentMissesOnSameKeyRunLoaderOnce() throws Exception {
        // Guards against a "cache stampede": 32 threads miss at the same instant, but only one of
        // them should hit the slow backend.
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder().maximumSize(10).build();
        AtomicInteger loaderCalls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        int threads = 32;
        CountDownLatch started = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    started.countDown();
                    return cache.get("hot", k -> {
                        loaderCalls.incrementAndGet();
                        try {
                            release.await(); // hold the load open until every thread has piled in
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "value";
                    });
                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(50); // let the stragglers reach get()
            release.countDown();

            for (Future<String> f : results) {
                assertEquals("value", f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loaderCalls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void waitersReceiveTheLoadersException() throws Exception {
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder().maximumSize(10).build();
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> owner = pool.submit(() -> cache.get("k", k -> {
                inLoader.countDown();
                try {
                    fail.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalArgumentException("bad key");
            }));
            assertTrue(inLoader.await(5, TimeUnit.SECONDS));
            Future<String> waiter = pool.submit(() -> cache.get("k", k -> "should not run"));
            Thread.sleep(50);
            fail.countDown();

            Exception ownerError = assertThrows(Exception.class, () -> owner.get(5, TimeUnit.SECONDS));
            assertTrue(ownerError.getCause() instanceof IllegalArgumentException);
            // The waiter either joined the failed load (same exception) or arrived after it finished
            // and ran its own loader; both are correct, neither may hang.
            try {
                assertEquals("should not run", waiter.get(5, TimeUnit.SECONDS));
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(e.getCause() instanceof IllegalArgumentException);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
