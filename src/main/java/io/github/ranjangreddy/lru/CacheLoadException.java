package io.github.ranjangreddy.lru;

/** Thrown by {@link Cache#get(Object, java.util.function.Function)} when the loader fails with a checked exception. */
public class CacheLoadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CacheLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
