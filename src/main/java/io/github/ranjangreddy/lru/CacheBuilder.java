package io.github.ranjangreddy.lru;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Fluent builder for {@link Cache} instances.
 *
 * <pre>{@code
 * Cache<String, User> users = CacheBuilder.<String, User>newBuilder()
 *         .maximumSize(10_000)
 *         .expireAfterWrite(Duration.ofMinutes(5))
 *         .concurrencyLevel(16)
 *         .removalListener((id, user, cause) -> log.info("{} left: {}", id, cause))
 *         .build();
 * }</pre>
 */
public final class CacheBuilder<K, V> {

    private int maximumSize = -1;
    private long defaultTtlNanos = 0;
    private long sweepIntervalNanos = 0;
    private int concurrencyLevel = 1;
    private Ticker ticker = Ticker.SYSTEM;
    private RemovalListener<K, V> removalListener;

    private CacheBuilder() {
    }

    public static <K, V> CacheBuilder<K, V> newBuilder() {
        return new CacheBuilder<>();
    }

    /** Required. The cache evicts least recently used entries once it holds this many. */
    public CacheBuilder<K, V> maximumSize(int maximumSize) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximumSize must be positive: " + maximumSize);
        }
        this.maximumSize = maximumSize;
        return this;
    }

    /** Default time-to-live for every entry, measured from when it was last written. */
    public CacheBuilder<K, V> expireAfterWrite(Duration ttl) {
        this.defaultTtlNanos = requirePositive(ttl, "ttl");
        return this;
    }

    /**
     * Runs {@link Cache#cleanUp()} on a background daemon thread at this interval, so expired entries
     * free memory even if they are never read again. Call {@link Cache#close()} to stop it.
     */
    public CacheBuilder<K, V> expirySweepInterval(Duration interval) {
        this.sweepIntervalNanos = requirePositive(interval, "interval");
        return this;
    }

    /**
     * Number of independently locked segments. 1 (the default) gives exact LRU; higher values give
     * better throughput under concurrent writes with approximate LRU. Rounded up to a power of two.
     */
    public CacheBuilder<K, V> concurrencyLevel(int level) {
        if (level <= 0) {
            throw new IllegalArgumentException("concurrencyLevel must be positive: " + level);
        }
        this.concurrencyLevel = level == 1 ? 1 : Integer.highestOneBit(level - 1) << 1;
        return this;
    }

    public CacheBuilder<K, V> removalListener(RemovalListener<K, V> listener) {
        this.removalListener = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Overrides the time source; intended for tests. */
    public CacheBuilder<K, V> ticker(Ticker ticker) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        return this;
    }

    public Cache<K, V> build() {
        if (maximumSize <= 0) {
            throw new IllegalStateException("maximumSize must be set");
        }
        if (concurrencyLevel == 1) {
            LruCache<K, V> cache = new LruCache<>(maximumSize, defaultTtlNanos, ticker, removalListener);
            if (sweepIntervalNanos > 0) {
                cache.startSweeper(newSweeperExecutor(), sweepIntervalNanos);
            }
            return cache;
        }
        StripedLruCache<K, V> cache = new StripedLruCache<>(
                maximumSize, concurrencyLevel, defaultTtlNanos, ticker, removalListener);
        if (sweepIntervalNanos > 0) {
            cache.startSweeper(newSweeperExecutor(), sweepIntervalNanos);
        }
        return cache;
    }

    private static ScheduledExecutorService newSweeperExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lru-cache-sweeper");
            t.setDaemon(true); // never keep the JVM alive just to expire entries
            return t;
        });
    }

    private static long requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + d);
        }
        return toNanos(d);
    }

    /** Duration to nanos, saturating instead of throwing for durations beyond ~292 years. */
    static long toNanos(Duration d) {
        try {
            return d.toNanos();
        } catch (ArithmeticException e) {
            return d.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
