package com.softino.notifier.internal;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Daemon, named thread factories so no SDK thread can keep the JVM alive. */
public final class NotifierThreads {

    private NotifierThreads() {
    }

    /**
     * A factory whose threads are daemons named {@code prefix + n} (n starts at 1).
     * Every pool the SDK owns uses one of these; there is no thread-per-message path.
     */
    public static ThreadFactory daemon(final String prefix) {
        final AtomicInteger counter = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }
}
