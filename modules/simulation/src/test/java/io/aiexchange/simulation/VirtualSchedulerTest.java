package io.aiexchange.simulation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VirtualSchedulerTest {
    @Test void equalTimeKeepsInsertionOrderIncludingMessagesScheduledDuringDelivery() {
        var scheduler = new VirtualScheduler<String>(4);
        scheduler.schedule(10, "first at ten");
        scheduler.schedule(3, "first to run");
        scheduler.schedule(10, "second at ten");

        assertEquals(new VirtualScheduler.Delivery<>(3, "first to run"), scheduler.poll());
        scheduler.schedule(10, "third at ten");
        assertEquals(new VirtualScheduler.Delivery<>(10, "first at ten"), scheduler.poll());
        assertEquals(new VirtualScheduler.Delivery<>(10, "second at ten"), scheduler.poll());
        assertEquals(new VirtualScheduler.Delivery<>(10, "third at ten"), scheduler.poll());
        assertNull(scheduler.poll());
        assertEquals(10, scheduler.now());
    }

    @Test void rejectsPastTimeAndOverflowWithoutDroppingPendingMessages() {
        var scheduler = new VirtualScheduler<String>(1);
        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(-1, "past"));
        scheduler.schedule(4, "original");
        assertThrows(IllegalStateException.class, () -> scheduler.schedule(3, "overflow"));
        assertEquals(1, scheduler.size());
        assertEquals("original", scheduler.poll().message());
        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(3, "past"));
        scheduler.schedule(4, "same time");
        assertEquals("same time", scheduler.poll().message());
    }
}
