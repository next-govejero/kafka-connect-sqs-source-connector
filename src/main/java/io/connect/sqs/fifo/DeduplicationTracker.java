package io.connect.sqs.fifo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks message deduplication IDs for FIFO queues to prevent duplicate processing.
 * Uses a time-based expiration window to automatically clean up old entries.
 */
public class DeduplicationTracker {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationTracker.class);

    // Maps deduplication ID to expiration timestamp
    private final Map<String, Long> deduplicationIds = new ConcurrentHashMap<>();
    private final long windowMs;

    /**
     * Creates a DeduplicationTracker with the specified window.
     *
     * @param windowMs Time window in milliseconds to track deduplication IDs
     */
    public DeduplicationTracker(long windowMs) {
        this.windowMs = windowMs;
        log.info("DeduplicationTracker initialized with window: {}ms", windowMs);
    }

    /**
     * Checks if a message with the given deduplication ID is a duplicate.
     * If not a duplicate, records the ID for future checks.
     *
     * @param deduplicationId The message deduplication ID
     * @return true if this is a duplicate message, false otherwise
     */
    public boolean isDuplicate(String deduplicationId) {
        if (deduplicationId == null || deduplicationId.isEmpty()) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        cleanupExpired(currentTime);

        // Check if ID exists and is not expired
        Long expirationTime = deduplicationIds.get(deduplicationId);
        if (expirationTime != null && currentTime < expirationTime) {
            log.debug("Duplicate message detected with deduplication ID: {}", deduplicationId);
            return true;
        }

        // Record the ID with expiration time
        deduplicationIds.put(deduplicationId, currentTime + windowMs);
        log.trace("Recorded deduplication ID: {}, expires at: {}", deduplicationId, currentTime + windowMs);

        return false;
    }

    /**
     * Removes an entry for a specific deduplication ID.
     * Useful when a message is successfully processed and should be removed from tracking.
     *
     * @param deduplicationId The message deduplication ID
     */
    public void remove(String deduplicationId) {
        if (deduplicationId != null) {
            deduplicationIds.remove(deduplicationId);
            log.trace("Removed deduplication ID: {}", deduplicationId);
        }
    }

    /**
     * Cleans up expired deduplication IDs.
     *
     * @param currentTime The current time in milliseconds
     */
    private void cleanupExpired(long currentTime) {
        int sizeBefore = deduplicationIds.size();
        deduplicationIds.entrySet().removeIf(entry -> currentTime >= entry.getValue());
        int removed = sizeBefore - deduplicationIds.size();

        if (removed > 0) {
            log.debug("Cleaned up {} expired deduplication IDs", removed);
        }
    }

    /**
     * Gets the number of tracked deduplication IDs.
     *
     * @return Number of tracked IDs
     */
    public int getTrackedCount() {
        return deduplicationIds.size();
    }

    /**
     * Clears all tracked deduplication IDs.
     */
    public void clear() {
        deduplicationIds.clear();
        log.debug("Cleared all deduplication tracking");
    }

    /**
     * Gets the configured deduplication window.
     *
     * @return Window in milliseconds
     */
    public long getWindowMs() {
        return windowMs;
    }
}
