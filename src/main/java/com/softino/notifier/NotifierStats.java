package com.softino.notifier;

/**
 * Point-in-time counters and gauges for the asynchronous send path.
 *
 * <p>Read one with {@link NotifierApi#stats()}. Values are cumulative for the life of the
 * client, except the depth and in-flight gauges which are instantaneous. Only send operations
 * are counted: a non-blocking status lookup uses the same engine but does not increment
 * {@code accepted}/{@code completed}/{@code failed}/{@code retries}/{@code rejected}/{@code dropped}.</p>
 *
 * <p>These are the Micrometer-free equivalents of:</p>
 *
 * <pre>
 * notifier.send.duration{outcome,priority}   timer
 * notifier.queue.depth{priority}             gauge
 * notifier.send.in_flight                    gauge
 * notifier.send.retries{reason}              counter
 * notifier.send.rejected{reason}             counter
 * notifier.send.dropped                      counter
 * </pre>
 */
public final class NotifierStats {

    private final long accepted;
    private final long completed;
    private final long failed;
    private final long retries;
    private final long rejected;
    private final long dropped;
    private final int inFlight;
    private final int highQueueDepth;
    private final int bulkQueueDepth;

    public NotifierStats(long accepted, long completed, long failed, long retries,
                         long rejected, long dropped, int inFlight,
                         int highQueueDepth, int bulkQueueDepth) {
        this.accepted = accepted;
        this.completed = completed;
        this.failed = failed;
        this.retries = retries;
        this.rejected = rejected;
        this.dropped = dropped;
        this.inFlight = inFlight;
        this.highQueueDepth = highQueueDepth;
        this.bulkQueueDepth = bulkQueueDepth;
    }

    /** Sends accepted into a lane (never counts rejected sends). */
    public long getAccepted() { return accepted; }

    /** Sends that finished with 2xx (hub accepted the notification). */
    public long getCompleted() { return completed; }

    /** Sends that finished with a typed failure, after any retries. */
    public long getFailed() { return failed; }

    /** Retries scheduled (not attempts made). */
    public long getRetries() { return retries; }

    /** Local refusals, either queue-full or closed-client. */
    public long getRejected() { return rejected; }

    /**
     * Accepted sends whose failure has no observer (fire-and-forget, or abandoned at close).
     * A failing {@code *Async} future is not counted here; it has an observer.
     */
    public long getDropped() { return dropped; }

    /** Sends currently dispatched and awaiting a hub response, across both lanes. */
    public int getInFlight() { return inFlight; }

    public int getHighQueueDepth() { return highQueueDepth; }

    public int getBulkQueueDepth() { return bulkQueueDepth; }

    /** Queue depth for one lane. */
    public int getQueueDepth(SendPriority priority) {
        return priority == SendPriority.HIGH ? highQueueDepth : bulkQueueDepth;
    }

    @Override
    public String toString() {
        return "NotifierStats{accepted=" + accepted
                + ", completed=" + completed
                + ", failed=" + failed
                + ", retries=" + retries
                + ", rejected=" + rejected
                + ", dropped=" + dropped
                + ", inFlight=" + inFlight
                + ", highQueueDepth=" + highQueueDepth
                + ", bulkQueueDepth=" + bulkQueueDepth + '}';
    }
}
