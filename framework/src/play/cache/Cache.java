package play.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;
import play.exceptions.CacheException;
import play.libs.Time;

import java.io.NotSerializableException;
import java.io.Serializable;
import java.util.Map;

/**
 * The Cache. Mainly an interface to memcached or EhCache.
 *
 * Note: When specifying expiration == "0s" (zero seconds) the actual expiration-time may vary between different cache implementations
 */
public abstract class Cache {
    private static final Logger logger = LoggerFactory.getLogger(Cache.class);

    /**
     * The underlying cache implementation
     */
    public static CacheImpl cacheImpl;

    /**
     * Set an element.
     * @param key Element key
     * @param value Element value
     * @param expiration Ex: 10s, 3mn, 8h
     */
    public static void set(String key, Object value, String expiration) {
        checkSerializable(value);
        cacheImpl.set(key, value, Time.parseDuration(expiration));
    }

    /**
     * Replace an element only if it already exists.
     * @param key Element key
     * @param value Element value
     * @param expiration Ex: 10s, 3mn, 8h
     */
    public static void replace(String key, Object value, String expiration) {
        checkSerializable(value);
        cacheImpl.replace(key, value, Time.parseDuration(expiration));
    }

    /**
     * Increment the element value (must be a Number).
     * @param key Element key 
     * @param by The incr value
     * @return The new value
     */
    public static long incr(String key, int by) {
        return cacheImpl.incr(key, by);
    }

    /**
     * Increment the element value (must be a Number) by 1.
     * @param key Element key 
     * @return The new value
     */
    public static long incr(String key) {
        return cacheImpl.incr(key, 1);
    }

    /**
     * Decrement the element value (must be a Number).
     * @param key Element key 
     * @param by The decr value
     * @return The new value
     */
    public static long decr(String key, int by) {
        return cacheImpl.decr(key, by);
    }

    /**
     * Decrement the element value (must be a Number) by 1.
     * @param key Element key 
     * @return The new value
     */
    public static long decr(String key) {
        return cacheImpl.decr(key, 1);
    }

    /**
     * Retrieve an object.
     * @param key The element key
     * @return The element value or null
     */
    public static <T> T get(String key) {
        return (T) cacheImpl.get(key);
    }

    /**
     * Bulk retrieve.
     * @param key List of keys
     * @return Map of keys &amp; values
     */
    public static Map<String, Object> get(String... key) {
        return cacheImpl.get(key);
    }

    /**
     * Delete an element from the cache.
     * @param key The element key
     */
    public static void delete(String key) {
        cacheImpl.delete(key);
    }

    /**
     * Clear all data from cache.
     */
    public static void clear() {
        if (cacheImpl != null) {
            cacheImpl.clear();
        }
    }

    /**
     * Initialize the cache system.
     */
    public static void init() {
        if ("enabled".equals(Play.configuration.getProperty("memcached", "disabled"))) {
            try {
                cacheImpl = MemcachedImpl.getInstance(true);
                logger.info("Connected to memcached");
            } catch (Exception e) {
                logger.error("Error while connecting to memcached", e);
                logger.warn("Fallback to local cache");
                cacheImpl = EhCacheImpl.newInstance();
            }
        } else {
            cacheImpl = EhCacheImpl.newInstance();
        }
    }

    /**
     * Stop the cache system.
     */
    public static void stop() {
        try {
            cacheImpl.stop();
        }
        catch (Exception e) {
            logger.error("Failed to stop the cache", e);
        }
    }

    /**
     * Utility that check that an object is serializable.
     */
    static void checkSerializable(Object value) {
        if(value != null && !(value instanceof Serializable)) {
            throw new CacheException("Cannot cache a non-serializable value of type " + value.getClass().getName(), new NotSerializableException(value.getClass().getName()));
        }
    }
}

