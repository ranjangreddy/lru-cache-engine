package io.github.ranjangreddy.lru;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A thread-safe LRU cache backed by a {@link HashMap} for O(1) lookup and an intrusive doubly
 * linked list for O(1) recency updates and eviction.
 *
 * <pre>
 *   head (sentinel) ⇄ [most recent] ⇄ ... ⇄ [least recent] ⇄ tail (sentinel)
 * </pre>
 *
 * <p>A single {@link ReentrantLock} guards the map and the list together, because every read also
 * mutates the list (moving the entry to the front). For higher write concurrency use a striped cache
 * via {@link CacheBuilder#concurrencyLevel(int)}.
 *
 * <p>Expiry is lazy: an expired entry is removed when it is next read, when it reaches the tail during
 * eviction, or when {@link #cleanUp()} runs (optionally on a background sweeper).
 */
public final class LruCache<K, V> implements Cache<K, V> {

    private static final Logger LOG = Logger.getLogger(LruCache.class.getName());
    private static final long NO_EXPIRY = Long.MAX_VALUE;

    private final int capacity;
    private final long defaultTtlNanos;
    private final Ticker ticker;
    private final RemovalListener<K, V> listener;
    private final StatsRecorder stats = new StatsRecorder();

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head = new Node<>(null, null, NO_EXPIRY);
    private final Node<K, V> tail = new Node<>(null, null, NO_EXPIRY);

    /** Loads in progress, so concurrent misses on the same key share one loader call. */
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    private volatile ScheduledExecutorService sweeper;

    LruCache(int capacity, long defaultTtlNanos, Ticker ticker, RemovalListener<K, V> listener) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.defaultTtlNanos = defaultTtlNanos;
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.listener = listener;
        // Sized so the map never rehashes while at capacity.
        this.map = new HashMap<>((int) Math.min((long) capacity * 4 / 3 + 1, Integer.MAX_VALUE));
        head.next = tail;
        tail.prev = head;
    }

    /** Convenience constructor for a plain LRU cache with no expiry. */
    public LruCache(int capacity) {
        this(capacity, 0, Ticker.SYSTEM, null);
    }

    // ---------------------------------------------------------------- reads

    @Override
    public V get(K key) {
        Objects.requireNonNull(key, "key");
        List<Removal<K, V>> removed = null;
        V result = null;
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                stats.recordMiss();
            } else if (isExpired(node, ticker.nanoTime())) {
                removed = new ArrayList<>(1);
                removed.add(expire(node));
                stats.recordMiss();
            } else {
                moveToFront(node);
                stats.recordHit();
                result = node.value;
            }
        } finally {
            lock.unlock();
        }
        notifyListener(removed);
        return result;
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");

        V cached = get(key);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<V> ours = new CompletableFuture<>();
        CompletableFuture<V> theirs = inFlight.putIfAbsent(key, ours);
        if (theirs != null) {
            return await(theirs);
        }
        try {
            // Another thread may have finished loading between our miss and claiming the key.
            V value = peek(key);
            if (value == null) {
                value = loader.apply(key);
                stats.recordLoadSuccess();
                if (value != null) {
                    put(key, value);
                }
            }
            ours.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            stats.recordLoadFailure();
            ours.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, ours);
        }
    }

    @Override
    public boolean containsKey(K key) {
        return peek(key) != null;
    }

    /** Reads a live value without touching recency or stats. */
    private V peek(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            return node == null || isExpired(node, ticker.nanoTime()) ? null : node.value;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- writes

    @Override
    public void put(K key, V value) {
        putInternal(key, value, defaultTtlNanos);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        putInternal(key, value, CacheBuilder.toNanos(ttl));
    }

    private void putInternal(K key, V value, long ttlNanos) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        // A put displaces at most one entry (the old value, or the tail when inserting into a full
        // cache), so track it in a local rather than allocating a list on this hot path.
        Removal<K, V> removed = null;
        lock.lock();
        try {
            long now = ticker.nanoTime();
            long expiresAt = deadline(now, ttlNanos);
            Node<K, V> node = map.get(key);
            if (node != null) {
                if (isExpired(node, now)) {
                    removed = new Removal<>(key, node.value, RemovalCause.EXPIRED);
                    stats.recordExpiration();
                } else {
                    removed = new Removal<>(key, node.value, RemovalCause.REPLACED);
                }
                node.value = value;
                node.expiresAt = expiresAt;
                moveToFront(node);
            } else {
                node = new Node<>(key, value, expiresAt);
                map.put(key, node);
                addToFront(node);
                if (map.size() > capacity) {
                    removed = evictTail(now);
                }
            }
        } finally {
            lock.unlock();
        }
        notifyListener(removed);
    }

    @Override
    public V remove(K key) {
        Objects.requireNonNull(key, "key");
        Removal<K, V> removal;
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            if (isExpired(node, ticker.nanoTime())) {
                removal = expire(node);
            } else {
                unlink(node);
                map.remove(key);
                removal = new Removal<>(key, node.value, RemovalCause.EXPLICIT);
            }
        } finally {
            lock.unlock();
        }
        notifyListener(removal);
        return removal.cause == RemovalCause.EXPLICIT ? removal.value : null;
    }

    @Override
    public void clear() {
        List<Removal<K, V>> removed;
        lock.lock();
        try {
            removed = new ArrayList<>(map.size());
            for (Node<K, V> n = head.next; n != tail; n = n.next) {
                removed.add(new Removal<>(n.key, n.value, RemovalCause.EXPLICIT));
            }
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            lock.unlock();
        }
        notifyListener(removed);
    }

    @Override
    public int cleanUp() {
        List<Removal<K, V>> removed = new ArrayList<>();
        lock.lock();
        try {
            long now = ticker.nanoTime();
            Node<K, V> n = head.next;
            while (n != tail) {
                Node<K, V> next = n.next;
                if (isExpired(n, now)) {
                    removed.add(expire(n));
                }
                n = next;
            }
        } finally {
            lock.unlock();
        }
        notifyListener(removed);
        return removed.size();
    }

    // ---------------------------------------------------------------- introspection

    @Override
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public CacheStats stats() {
        return stats.snapshot();
    }

    /** Keys from most to least recently used. Intended for debugging and tests. */
    public List<K> keysByRecency() {
        lock.lock();
        try {
            List<K> keys = new ArrayList<>(map.size());
            for (Node<K, V> n = head.next; n != tail; n = n.next) {
                keys.add(n.key);
            }
            return keys;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    void startSweeper(ScheduledExecutorService executor, long intervalNanos) {
        this.sweeper = executor;
        executor.scheduleWithFixedDelay(this::safeCleanUp, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
    }

    private void safeCleanUp() {
        try {
            cleanUp();
        } catch (RuntimeException e) {
            // An exception would silently cancel all future runs of a scheduled task.
            LOG.log(Level.WARNING, "Expiry sweep failed", e);
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService s = sweeper;
        if (s != null) {
            s.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- list + expiry helpers (lock held)

    private static long deadline(long now, long ttlNanos) {
        if (ttlNanos <= 0) {
            return NO_EXPIRY;
        }
        long deadline = now + ttlNanos;
        return deadline < now ? NO_EXPIRY : deadline; // saturate on overflow
    }

    private static boolean isExpired(Node<?, ?> node, long now) {
        return node.expiresAt != NO_EXPIRY && now >= node.expiresAt;
    }

    private Removal<K, V> expire(Node<K, V> node) {
        unlink(node);
        map.remove(node.key);
        stats.recordExpiration();
        return new Removal<>(node.key, node.value, RemovalCause.EXPIRED);
    }

    private Removal<K, V> evictTail(long now) {
        Node<K, V> lru = tail.prev;
        if (isExpired(lru, now)) {
            return expire(lru);
        }
        unlink(lru);
        map.remove(lru.key);
        stats.recordEviction();
        return new Removal<>(lru.key, lru.value, RemovalCause.SIZE);
    }

    private void addToFront(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }

    private void moveToFront(Node<K, V> node) {
        if (head.next != node) {
            unlink(node);
            addToFront(node);
        }
    }

    // ---------------------------------------------------------------- listener dispatch (lock NOT held)

    /**
     * Listeners run outside the lock: user code that is slow, blocks, or calls back into this cache
     * must never be able to stall or deadlock other threads.
     */
    private void notifyListener(List<Removal<K, V>> removed) {
        if (listener == null || removed == null) {
            return;
        }
        for (Removal<K, V> r : removed) {
            notifyListener(r);
        }
    }

    private void notifyListener(Removal<K, V> r) {
        if (listener == null || r == null) {
            return;
        }
        try {
            listener.onRemoval(r.key, r.value, r.cause);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Removal listener threw for key " + r.key, e);
        }
    }

    private static <V> V await(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new CacheLoadException("Loader failed", cause);
        }
    }

    private static final class Node<K, V> {
        final K key;
        V value;
        long expiresAt;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value, long expiresAt) {
            this.key = key;
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Removal<K, V> {
        final K key;
        final V value;
        final RemovalCause cause;

        Removal(K key, V value, RemovalCause cause) {
            this.key = key;
            this.value = value;
            this.cause = cause;
        }
    }
}
