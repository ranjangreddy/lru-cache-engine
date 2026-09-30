# LRU Cache Engine

[![CI](https://github.com/ranjangreddy/lru-cache-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/ranjangreddy/lru-cache-engine/actions/workflows/ci.yml)

A thread-safe, generic, in-memory LRU cache for Java 11+, with zero runtime dependencies.

The textbook LRU cache (hash map + doubly linked list, O(1) `get`/`put`) is the starting point. This
project adds what a cache needs once real traffic and many threads hit it:

| Feature | What it solves |
|---|---|
| **TTL expiry**: a default `expireAfterWrite` plus per-entry TTL overrides | Stale data. Entries die on schedule even while they're still popular. |
| **Background expiry sweeper** (optional) | Memory held by expired entries that are never read again |
| **Single-flight loading**: `get(key, loader)` | Cache stampedes. 50 threads missing on the same key trigger **one** backend call, not 50. |
| **Removal listeners** with a cause (`SIZE`, `EXPIRED`, `REPLACED`, `EXPLICIT`) | Hooks for metrics, write-behind, closing resources, and debugging evictions |
| **Lock striping**: `concurrencyLevel(n)` | A single lock becoming the bottleneck under concurrent writes |
| **Stats**: hit rate, misses, evictions, expirations, loads | Knowing whether the cache is actually helping |
| **Injectable clock** (`Ticker`) | Deterministic expiry tests that never `Thread.sleep` |

## Usage

```java
Cache<String, User> users = CacheBuilder.<String, User>newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .expirySweepInterval(Duration.ofSeconds(30))
        .concurrencyLevel(16)
        .removalListener((id, user, cause) -> log.debug("{} left the cache: {}", id, cause))
        .build();

// Read-through: loads from the DB on a miss, exactly once even under concurrent misses
User u = users.get(userId, id -> userRepository.findById(id));

// Per-entry TTL overrides the default
users.put("session:abc", guest, Duration.ofSeconds(30));

System.out.println(users.stats());
// CacheStats{hits=9120, misses=880, hitRate=0.912, evictions=0, expirations=41, loads=880, loadFailures=0}

users.close(); // stops the sweeper thread
```

Want a plain LRU cache with nothing extra? Use `new LruCache<>(capacity)`.

## Design

### Core structure: O(1) everything

```
HashMap<K, Node>              key → node, O(1) lookup
        │
        ▼
head ⇄ [MRU] ⇄ [ ] ⇄ [ ] ⇄ [LRU] ⇄ tail      intrusive doubly linked list, O(1) move/unlink
(sentinel)                          (sentinel)
```

- **get**: look up the node, unlink it, relink it after `head`.
- **put**: insert after `head`. If the cache is over capacity, unlink `tail.prev` and drop it from the map.
- **Sentinels** at both ends remove every null check from the link/unlink code.

### Thread safety

`LruCache` guards the map and the list with one `ReentrantLock`. A `ReadWriteLock` wouldn't help:
in an LRU cache **every read is a write**, because it moves the node to the front of the list.

`StripedLruCache` splits keys across N independent `LruCache` segments, each with its own lock, so threads
working on different keys don't block each other. The cost is that recency becomes per-segment, so
eviction is close to global LRU but not exact. This is the same trade Java 7's segmented
`ConcurrentHashMap` made.

**A bug the benchmark caught:** the first version picked a segment with `hash & (segments - 1)`, the
low bits of the hash. But each segment's `HashMap` *also* picks buckets from the low bits. So every key in
a segment shared the same low 4 bits and crowded into 1/16th of that segment's buckets. The striped cache
ran **~2.5× slower** than the single-lock one. Selecting the segment from the *top* bits of a
Fibonacci hash (`hash * 0x9E3779B9 >>> shift`) fixed it (see `StripedLruCache#segmentFor`).

### Removal listeners run outside the lock

Removals are collected while the lock is held, and listeners are called only **after** it's released.
This means a slow or blocking listener can't stall other threads. A listener can safely call back into
the cache (a re-entrant read, for example). And a listener that throws is logged and ignored, so it can't
corrupt the cache or fail the `put` that triggered it. `RemovalListenerTest` covers all three cases.

### Single-flight loading

`get(key, loader)` claims the key in a `ConcurrentHashMap<K, CompletableFuture<V>>` with `putIfAbsent`.
The thread that wins the claim runs the loader, and every other thread waits on the same future. After
claiming, the winner re-checks the cache, because a load may have finished between its miss and its claim.
Loader exceptions are passed to every waiter and never cached.

### Expiry

Expiry is lazy by default, with no extra thread: an expired entry is removed when it's read, when it
reaches the tail, or when `cleanUp()` runs. `expirySweepInterval` schedules `cleanUp()` on a daemon
thread so memory is freed even for keys that are never read again. Deadlines use `System.nanoTime()`
(which only moves forward) rather than wall-clock time, so a system clock change can't expire everything
at once or keep entries alive forever.

## Benchmark

`ThroughputBenchmark` is a mixed workload: 80% `get` and 20% `put`, with skewed keys (a hot set plus a long
tail), a 40k key space and a capacity of 10k. It compares the caches against the standard JDK approach:
`Collections.synchronizedMap(new LinkedHashMap<>(…, true))` with `removeEldestEntry`.

Apple M3 (4 performance + 4 efficiency cores), OpenJDK 11, ops/sec:

| Threads | synchronized `LinkedHashMap` | `LruCache` | `StripedLruCache` (16) |
|---:|---:|---:|---:|
| 1 | 19.3M | 12.8M | 11.2M |
| 4 | 4.9M | 7.1M | **9.8M** |
| 8 | 5.0M | 7.0M | 7.4M |

How to read these:
- **Single-threaded, the JDK class wins.** `LinkedHashMap` stores its links inside its own hash entries,
  while this cache keeps a separate node per entry, and TTL bookkeeping and stats add work on every call.
  With one thread there's no contention to win back.
- **Once threads compete, the JDK class collapses** (19.3M → 5.0M), because `synchronized` does badly
  under contention. At 4 threads, striping is **2× faster than the JDK baseline** and 1.4× faster than
  the single-lock cache.
- **At 8 threads the gains level off.** Half of the M3's cores are slower efficiency cores, and this
  workload's ~60% miss rate means constant allocation.

This is a simple timed loop, not a JMH harness (JMH is the standard Java benchmarking tool), so treat the
numbers as relative. Run it yourself:

```bash
mvn -q package -DskipTests
java -cp target/classes io.github.ranjangreddy.lru.bench.ThroughputBenchmark 4
```

## Tests

```bash
mvn test
```

30 JUnit 5 tests, including:
- **Eviction order, recency promotion, capacity edge cases.** `LruCacheTest`
- **TTL:** default vs per-entry, "reads don't extend TTL", "rewrites reset TTL", sweeper. `ExpiryTest`,
  using a fake clock, so these run in milliseconds with no sleeps.
- **Stampede protection:** 32 threads miss on one key, and the loader runs once. Also error
  propagation to waiting threads. `LoaderTest`
- **Concurrency stress:** 16 threads × 50k mixed operations on both cache types, checking that size never
  exceeds capacity, no key ever returns another key's value, and `inserts == size + removals`.
  `ConcurrencyTest`

## Limitations and possible next steps

- **Size is counted in entries, not bytes.** A `weigher` function could support memory-bounded caches.
- **Plain LRU can be flushed by one big scan** of rarely used keys. Admission policies such as TinyLFU (used
  by Caffeine) or segmented LRU keep frequently used entries safe from that.
- **Reads take a lock.** Caffeine avoids this by recording accesses in buffers and applying them in
  batches. That's much faster, but far more complex.

## License

MIT
