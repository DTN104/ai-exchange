package io.aiexchange.simulation;

import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;

final class VirtualScheduler<T> {
    record Delivery<T>(long timeNanos, T message) {}
    private record Entry<T>(long timeNanos, long sequence, T message) {}

    private final int capacity;
    private final PriorityQueue<Entry<T>> queue = new PriorityQueue<>(
            Comparator.<Entry<T>>comparingLong(Entry::timeNanos).thenComparingLong(Entry::sequence));
    private long now;
    private long sequence;

    VirtualScheduler(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Scheduler capacity must be positive");
        this.capacity = capacity;
    }

    void schedule(long timeNanos, T message) {
        Objects.requireNonNull(message, "Scheduled message");
        if (timeNanos < now) throw new IllegalArgumentException("Cannot schedule in the past");
        if (queue.size() == capacity) throw new IllegalStateException("Scheduled message limit reached");
        sequence = Math.incrementExact(sequence);
        queue.add(new Entry<>(timeNanos, sequence, message));
    }

    Delivery<T> poll() {
        var entry = queue.poll();
        if (entry == null) return null;
        now = entry.timeNanos();
        return new Delivery<>(now, entry.message());
    }

    long now() { return now; }
    int size() { return queue.size(); }
}
