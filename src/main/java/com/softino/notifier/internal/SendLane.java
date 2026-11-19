package com.softino.notifier.internal;

import com.softino.notifier.SendPriority;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * One priority lane: a bounded FIFO, a fixed set of dispatcher threads and a reserved HTTP
 * concurrency quota. HIGH and BULK never share a queue or a permit, so bulk traffic cannot
 * starve OTP traffic.
 */
final class SendLane {

    final SendPriority priority;
    final ArrayBlockingQueue<SendTask<?>> queue;
    final Semaphore permits;
    final int maxInFlight;

    SendLane(SendPriority priority, int capacity, int maxInFlight) {
        this.priority = priority;
        this.queue = new ArrayBlockingQueue<SendTask<?>>(capacity);
        this.maxInFlight = maxInFlight;
        this.permits = new Semaphore(maxInFlight);
    }
}
