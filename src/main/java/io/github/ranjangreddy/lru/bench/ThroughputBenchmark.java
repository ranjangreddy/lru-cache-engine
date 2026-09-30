package io.github.ranjangreddy.lru.bench;

import io.github.ranjangreddy.lru.Cache;
import io.github.ranjangreddy.lru.CacheBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * Rough multi-threaded throughput comparison. Not a JMH harness, so treat numbers as relative, not
 * absolute: warm-up is a single untimed round and results vary with hardware.
 *
 * <pre>
 * mvn -q package -DskipTests
 * java -cp target/classes io.github.ranjangreddy.lru.bench.ThroughputBenchmark [threads]
 * </pre>
 */
public final class ThroughputBenchmark {

    private static final int CAPACITY = 10_000;
    private static final int KEY_SPACE = 40_000;
    private static final long DURATION_MS = 2_000;

    interface Target {
        Integer get(Integer key);
        void put(Integer key, Integer value);
    }

    public static void main(String[] args) throws Exception {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : Runtime.getRuntime().availableProcessors();
        System.out.printf("threads=%d capacity=%d keySpace=%d workload=80%% get / 20%% put, skewed keys%n%n",
                threads, CAPACITY, KEY_SPACE);

        run("synchronized LinkedHashMap (JDK baseline)", threads, synchronizedLinkedHashMap());
        run("LruCache (single lock)", threads, of(CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(CAPACITY).build()));
        run("StripedLruCache (16 segments)", threads, of(CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(CAPACITY).concurrencyLevel(16).build()));
    }

    private static void run(String name, int threads, Target target) throws Exception {
        measure(threads, target, DURATION_MS / 2); // warm-up so the JIT compiles the hot paths
        long ops = measure(threads, target, DURATION_MS);
        System.out.printf("%-45s %,12d ops/s%n", name, ops * 1000 / DURATION_MS);
    }

    private static long measure(int threads, Target target, long durationMs) throws InterruptedException {
        LongAdder ops = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long[] deadline = new long[1];
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                long local = 0;
                while (System.nanoTime() < deadline[0]) {
                    // Squaring a uniform value skews toward low keys: a hot set plus a long tail,
                    // which is what real cache traffic looks like.
                    double u = rnd.nextDouble();
                    int key = (int) (u * u * KEY_SPACE);
                    if (rnd.nextInt(5) == 0) {
                        target.put(key, key);
                    } else if (target.get(key) == null) {
                        target.put(key, key);
                    }
                    local++;
                }
                ops.add(local);
                done.countDown();
            });
            worker.setDaemon(true);
            worker.start();
        }
        deadline[0] = System.nanoTime() + durationMs * 1_000_000;
        start.countDown();
        done.await();
        return ops.sum();
    }

    private static Target of(Cache<Integer, Integer> cache) {
        return new Target() {
            public Integer get(Integer key) { return cache.get(key); }
            public void put(Integer key, Integer value) { cache.put(key, value); }
        };
    }

    private static Target synchronizedLinkedHashMap() {
        Map<Integer, Integer> map = Collections.synchronizedMap(new LinkedHashMap<>(CAPACITY * 4 / 3 + 1, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                return size() > CAPACITY;
            }
        });
        return new Target() {
            public Integer get(Integer key) { return map.get(key); }
            public void put(Integer key, Integer value) { map.put(key, value); }
        };
    }
}
