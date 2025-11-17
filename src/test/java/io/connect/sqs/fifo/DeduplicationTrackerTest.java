package io.connect.sqs.fifo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeduplicationTrackerTest {

    private DeduplicationTracker tracker;

    @BeforeEach
    void setUp() {
        // Default: 5 minute window
        tracker = new DeduplicationTracker(300000L);
    }

    @Test
    void shouldTrackNewDeduplicationId() {
        boolean isDuplicate = tracker.isDuplicate("dedup-id-1");

        assertThat(isDuplicate).isFalse();
        assertThat(tracker.getTrackedCount()).isEqualTo(1);
    }

    @Test
    void shouldDetectDuplicateWithinWindow() {
        tracker.isDuplicate("dedup-id-1");
        boolean isDuplicate = tracker.isDuplicate("dedup-id-1");

        assertThat(isDuplicate).isTrue();
        assertThat(tracker.getTrackedCount()).isEqualTo(1);
    }

    @Test
    void shouldTrackMultipleUniqueIds() {
        assertThat(tracker.isDuplicate("dedup-id-1")).isFalse();
        assertThat(tracker.isDuplicate("dedup-id-2")).isFalse();
        assertThat(tracker.isDuplicate("dedup-id-3")).isFalse();

        assertThat(tracker.getTrackedCount()).isEqualTo(3);
    }

    @Test
    void shouldNotMarkNullAsDuplicate() {
        boolean isDuplicate = tracker.isDuplicate(null);

        assertThat(isDuplicate).isFalse();
        assertThat(tracker.getTrackedCount()).isEqualTo(0);
    }

    @Test
    void shouldNotMarkEmptyStringAsDuplicate() {
        boolean isDuplicate = tracker.isDuplicate("");

        assertThat(isDuplicate).isFalse();
        assertThat(tracker.getTrackedCount()).isEqualTo(0);
    }

    @Test
    void shouldRemoveDeduplicationId() {
        tracker.isDuplicate("dedup-id-1");
        assertThat(tracker.getTrackedCount()).isEqualTo(1);

        tracker.remove("dedup-id-1");

        assertThat(tracker.getTrackedCount()).isEqualTo(0);
        // Should be able to process again after removal
        assertThat(tracker.isDuplicate("dedup-id-1")).isFalse();
    }

    @Test
    void shouldClearAllTrackedIds() {
        tracker.isDuplicate("dedup-id-1");
        tracker.isDuplicate("dedup-id-2");
        tracker.isDuplicate("dedup-id-3");

        assertThat(tracker.getTrackedCount()).isEqualTo(3);

        tracker.clear();

        assertThat(tracker.getTrackedCount()).isEqualTo(0);
    }

    @Test
    void shouldExpireOldEntriesAfterWindow() throws InterruptedException {
        // Use very short window for testing
        DeduplicationTracker shortWindowTracker = new DeduplicationTracker(50L);

        shortWindowTracker.isDuplicate("dedup-id-1");
        assertThat(shortWindowTracker.isDuplicate("dedup-id-1")).isTrue();

        // Wait for expiration
        Thread.sleep(60);

        // Should no longer be a duplicate after window expires
        assertThat(shortWindowTracker.isDuplicate("dedup-id-1")).isFalse();
    }

    @Test
    void shouldReturnConfiguredWindow() {
        assertThat(tracker.getWindowMs()).isEqualTo(300000L);

        DeduplicationTracker customTracker = new DeduplicationTracker(60000L);
        assertThat(customTracker.getWindowMs()).isEqualTo(60000L);
    }

    @Test
    void shouldHandleConcurrentAccess() throws InterruptedException {
        DeduplicationTracker concurrentTracker = new DeduplicationTracker(60000L);

        // Simulate concurrent access
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                concurrentTracker.isDuplicate("id-" + i);
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                concurrentTracker.isDuplicate("id-" + (i + 100));
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertThat(concurrentTracker.getTrackedCount()).isEqualTo(200);
    }

    @Test
    void shouldRemoveNonExistentIdGracefully() {
        // Should not throw exception
        tracker.remove("non-existent-id");
        assertThat(tracker.getTrackedCount()).isEqualTo(0);
    }

    @Test
    void shouldHandleRemoveWithNull() {
        // Should not throw exception
        tracker.remove(null);
        assertThat(tracker.getTrackedCount()).isEqualTo(0);
    }
}
