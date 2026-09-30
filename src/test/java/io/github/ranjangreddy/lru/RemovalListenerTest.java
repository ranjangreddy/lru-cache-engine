package io.github.ranjangreddy.lru;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemovalListenerTest {

    private final List<String> events = new ArrayList<>();

    private Cache<String, Integer> cache(int size) {
        return CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(size)
                .removalListener((k, v, cause) -> events.add(k + "=" + v + ":" + cause))
                .build();
    }

    @Test
    void reportsEachCause() {
        Cache<String, Integer> cache = cache(2);
        cache.put("a", 1);
        cache.put("a", 2);    // REPLACED a=1
        cache.put("b", 3);
        cache.put("c", 4);    // SIZE a=2
        cache.remove("b");    // EXPLICIT b=3
        cache.clear();        // EXPLICIT c=4

        assertEquals(List.of("a=1:REPLACED", "a=2:SIZE", "b=3:EXPLICIT", "c=4:EXPLICIT"), events);
    }

    @Test
    void listenerMayCallBackIntoTheCache() {
        // Would deadlock if listeners ran while the cache lock was held by another thread,
        // and would corrupt the list if they ran mid-mutation.
        List<Integer> archive = new ArrayList<>();
        AtomicReference<Cache<String, Integer>> holder = new AtomicReference<>();
        holder.set(CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(1)
                .removalListener((k, v, cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        archive.add(v);
                        holder.get().get("probe"); // re-entrant read
                    }
                })
                .build());

        holder.get().put("a", 1);
        holder.get().put("b", 2);

        assertEquals(List.of(1), archive);
        assertEquals(2, holder.get().get("b"));
    }

    @Test
    void throwingListenerDoesNotBreakTheCache() {
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(1)
                .removalListener((k, v, cause) -> {
                    throw new IllegalStateException("boom");
                })
                .build();

        cache.put("a", 1);
        cache.put("b", 2); // listener throws, put still succeeds

        assertEquals(2, cache.get("b"));
        assertEquals(1, cache.size());
    }
}
