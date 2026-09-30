# LRU Cache Engine

[![CI](https://github.com/ranjangreddy/lru-cache-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/ranjangreddy/lru-cache-engine/actions/workflows/ci.yml)

A fast, thread-safe in-memory cache for Java.

## What is it?

A cache keeps recently used data in memory so you don't have to fetch it again from somewhere slow,
like a database.

It has a size limit. When it's full, it removes the item that hasn't been used for the longest
time. That's what **LRU (Least Recently Used)** means.

## Features

- **Fast:** `get` and `put` take O(1) time, however big the cache is.
- **Thread-safe:** many threads can use it at once.
- **Expiry:** entries can expire after a set time, like 5 minutes.
- **No duplicate loads:** if many threads ask for the same missing key at once, the data is loaded only once.
- **Removal alerts:** get notified when an entry is removed, and why.
- **Stats:** see your hit rate, misses and evictions.
- **Striped mode:** splits the cache into sections so threads wait on each other less.

## Try it

Run the demo to watch the cache work step by step (see `Demo.java`):

```bash
mvn -q compile
java -cp target/classes io.github.ranjangreddy.lru.Demo
```

```
Added A, B, C         -> order (newest first): [C, B, A]
Used A                -> A moves to the front: [A, C, B]
Added D (cache full!) -> B was oldest, so B is removed: [D, A, C]
...
Request 1: User#7  took 519 ms
Request 2: User#7  took 0 ms      <- came from the cache
```

## Quick example

```java
Cache<String, User> users = CacheBuilder.<String, User>newBuilder()
        .maximumSize(10_000)                      // hold up to 10,000 users
        .expireAfterWrite(Duration.ofMinutes(5))  // forget after 5 minutes
        .build();

users.put("u1", alice);
User u = users.get("u1");   // fast: comes from memory

// Load from the database only if it's not already cached
User bob = users.get("u2", id -> database.findUser(id));

System.out.println(users.stats());  // hits, misses, hit rate, ...
```

Just need a simple LRU cache? Use `new LruCache<>(100)`.

## How it works

It uses two data structures together:

1. **A hash map** to find any item instantly.
2. **A doubly linked list** to keep items in order, most recently used at the front.

```
front (newest)  [C] ⇄ [B] ⇄ [A]  back (oldest)
```

- **get:** find the item and move it to the front.
- **put:** add the item to the front. If the cache is full, remove the item at the back.

Both steps take the same time however big the cache is.

## Benchmark

Operations per second with 4 threads on an Apple M3:

| Java's `LinkedHashMap` (synchronized) | This cache | This cache (striped) |
|---:|---:|---:|
| 4.9M | 7.1M | **9.8M** |

In striped mode it's about **2× faster** than Java's built-in approach when several threads compete.
With only one thread, Java's built-in class is faster.

Run it yourself:

```bash
mvn -q package -DskipTests
java -cp target/classes io.github.ranjangreddy.lru.bench.ThroughputBenchmark 4
```

## Run the tests

```bash
mvn test
```

The 30 tests cover eviction order, expiry, loading, removal alerts, and 16 threads using the cache at once.

## Project structure

| File | What it does |
|---|---|
| `Demo.java` | **Start here.** Runnable examples with a `main` method |
| `LruCache.java` | The main cache: hash map + linked list |
| `StripedLruCache.java` | Splits the cache into sections for more parallel access |
| `CacheBuilder.java` | Sets up a cache with the options you want |
| `CacheStats.java` | Hit, miss and eviction counts |
| `ThroughputBenchmark.java` | Speed test |

## License

MIT
