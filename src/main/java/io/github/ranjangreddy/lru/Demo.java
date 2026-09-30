package io.github.ranjangreddy.lru;

import java.time.Duration;

/**
 * Run this to see the cache in action:
 *
 * <pre>
 * mvn -q compile
 * java -cp target/classes io.github.ranjangreddy.lru.Demo
 * </pre>
 */
public class Demo {

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=== 1. Basic LRU: a cache that holds only 3 items ===");
        LruCache<String, String> cache = new LruCache<>(3);

        cache.put("A", "Apple");
        cache.put("B", "Banana");
        cache.put("C", "Cherry");
        System.out.println("Added A, B, C         -> order (newest first): " + cache.keysByRecency());

        cache.get("A");
        System.out.println("Used A                -> A moves to the front: " + cache.keysByRecency());

        cache.put("D", "Date");
        System.out.println("Added D (cache full!) -> B was oldest, so B is removed: " + cache.keysByRecency());
        System.out.println("get(\"B\") = " + cache.get("B") + "   (gone)");
        System.out.println("get(\"A\") = " + cache.get("A") + "  (still here)");


        System.out.println("\n=== 2. Expiry: items that disappear after 1 second ===");
        Cache<String, String> sessions = CacheBuilder.<String, String>newBuilder()
                .maximumSize(100)
                .expireAfterWrite(Duration.ofSeconds(1))
                .build();

        sessions.put("user42", "logged-in");
        System.out.println("Right away:     " + sessions.get("user42"));
        Thread.sleep(1100);
        System.out.println("After 1.1 sec:  " + sessions.get("user42") + "  (expired)");


        System.out.println("\n=== 3. Loading: fetch from a 'database' only when needed ===");
        Cache<Integer, String> users = CacheBuilder.<Integer, String>newBuilder()
                .maximumSize(100)
                .build();

        for (int i = 1; i <= 3; i++) {
            long start = System.nanoTime();
            String name = users.get(7, id -> slowDatabaseLookup(id));
            long ms = (System.nanoTime() - start) / 1_000_000;
            System.out.println("Request " + i + ": " + name + "  took " + ms + " ms");
        }
        System.out.println("-> Only the first request hit the slow database. The rest came from the cache.");


        System.out.println("\n=== 4. Removal alerts: find out when and why items leave ===");
        Cache<String, Integer> scores = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(2)
                .removalListener((key, value, cause) ->
                        System.out.println("   removed " + key + "=" + value + " because: " + cause))
                .build();

        scores.put("alice", 10);
        scores.put("bob", 20);
        scores.put("alice", 15);  // REPLACED: alice had an old value
        scores.put("carol", 30);  // SIZE: cache full, bob was oldest
        scores.remove("alice");   // EXPLICIT: we removed it ourselves


        System.out.println("\n=== 5. Stats: is the cache helping? ===");
        System.out.println(users.stats());
    }

    /** Pretend this is a real database call that takes 500 ms. */
    private static String slowDatabaseLookup(int id) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "User#" + id;
    }
}
