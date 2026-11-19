package com.softino.notifier.metrics;

import com.softino.notifier.NotifierStats;
import com.softino.notifier.SendEvent;
import com.softino.notifier.SendListener;
import com.softino.notifier.SendOutcome;
import com.softino.notifier.SendPriority;
import com.softino.notifier.exception.NotifierClosedException;
import com.softino.notifier.exception.NotifierException;
import com.softino.notifier.exception.NotifierQueueFullException;
import com.softino.notifier.exception.NotifierRateLimitedException;
import com.softino.notifier.exception.NotifierTimeoutException;
import com.softino.notifier.exception.NotifierUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Optional Micrometer binding for the asynchronous send path. Register it as the client's
 * {@link SendListener}:
 *
 * <pre>{@code
 * NotifierApi api = new NotifierApi(NotifierConfig.builder("KEY")
 *         .sendListener(new MicrometerSendListener(registry, apiStatsSupplier))
 *         .build());
 * }</pre>
 *
 * <p>{@code micrometer-core} is an optional dependency: use this class only when the
 * application already has Micrometer. Otherwise implement {@link SendListener} directly. The
 * stats supplier may be {@code null}; in that case the two gauges are not registered.</p>
 *
 * <p>Metric names and tags:</p>
 *
 * <pre>
 * notifier.send.duration{outcome,priority}   timer
 * notifier.queue.depth{priority}             gauge
 * notifier.send.in_flight                    gauge
 * notifier.send.retries{reason,priority}     counter
 * notifier.send.rejected{reason,priority}    counter
 * notifier.send.dropped{priority}            counter
 * </pre>
 */
public final class MicrometerSendListener implements SendListener {

    private final MeterRegistry registry;
    /**
     * Strong reference to the gauge state. Micrometer keeps only a weak reference to the state
     * object, so without this field the supplier (and the client it captures) can be collected
     * and the gauges would silently report NaN.
     */
    private final Supplier<NotifierStats> statsSupplier;
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<String, Timer>();
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<String, Counter>();

    /**
     * @param registry      the application's Micrometer registry
     * @param statsSupplier a supplier returning fresh {@link NotifierStats}, typically
     *                      {@code api::stats}; may be {@code null} to skip gauges. A supplier
     *                      is required because a gauge holds a weak reference to the
     *                      {@code NotifierApi} and must not pin it.
     */
    public MicrometerSendListener(MeterRegistry registry, Supplier<NotifierStats> statsSupplier) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.statsSupplier = statsSupplier;
        if (statsSupplier != null) {
            for (final SendPriority priority : SendPriority.values()) {
                Gauge.builder("notifier.queue.depth", statsSupplier,
                                supplier -> {
                                    NotifierStats stats = supplier.get();
                                    return stats == null ? 0 : stats.getQueueDepth(priority);
                                })
                        .tag("priority", tag(priority))
                        .register(registry);
            }
            Gauge.builder("notifier.send.in_flight", statsSupplier,
                            supplier -> {
                                NotifierStats stats = supplier.get();
                                return stats == null ? 0 : stats.getInFlight();
                            })
                    .register(registry);
        }
    }

    @Override
    public void onCompleted(SendEvent event, SendOutcome outcome, long durationMillis,
                            NotifierException error) {
        String outcomeTag = outcome == null ? "unknown" : outcome.name().toLowerCase(Locale.ROOT);
        timer(outcomeTag, tag(event)).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onRetry(SendEvent event, int nextAttempt, long delayMillis, NotifierException reason) {
        counter("notifier.send.retries", "reason", reason(reason), tag(event)).increment();
    }

    @Override
    public void onRejected(SendEvent event, NotifierException reason) {
        counter("notifier.send.rejected", "reason", reason(reason), tag(event)).increment();
    }

    @Override
    public void onDropped(SendEvent event, NotifierException reason) {
        counter("notifier.send.dropped", tag(event)).increment();
    }

    private Timer timer(String outcome, String priority) {
        String key = outcome + '|' + priority;
        Timer timer = timers.get(key);
        if (timer == null) {
            Timer created = Timer.builder("notifier.send.duration")
                    .tag("outcome", outcome)
                    .tag("priority", priority)
                    .register(registry);
            Timer raced = timers.putIfAbsent(key, created);
            timer = raced == null ? created : raced;
        }
        return timer;
    }

    private Counter counter(String name, String priority) {
        String key = name + '|' + priority;
        Counter counter = counters.get(key);
        if (counter == null) {
            Counter created = Counter.builder(name).tag("priority", priority).register(registry);
            Counter raced = counters.putIfAbsent(key, created);
            counter = raced == null ? created : raced;
        }
        return counter;
    }

    private Counter counter(String name, String tagName, String tagValue, String priority) {
        String key = name + '|' + tagName + '|' + tagValue + '|' + priority;
        Counter counter = counters.get(key);
        if (counter == null) {
            Counter created = Counter.builder(name)
                    .tag(tagName, tagValue)
                    .tag("priority", priority)
                    .register(registry);
            Counter raced = counters.putIfAbsent(key, created);
            counter = raced == null ? created : raced;
        }
        return counter;
    }

    private static String tag(SendEvent event) {
        if (event == null || event.getPriority() == null) return "unknown";
        return tag(event.getPriority());
    }

    private static String tag(SendPriority priority) {
        return priority.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Maps a typed error to a bounded tag value. The exception message is never used as a tag,
     * so cardinality cannot explode and no payload can leak into metrics.
     */
    static String reason(NotifierException error) {
        if (error == null) return "none";
        if (error instanceof NotifierQueueFullException) return "queue_full";
        if (error instanceof NotifierClosedException) return "closed";
        if (error instanceof NotifierRateLimitedException) return "rate_limited";
        if (error instanceof NotifierTimeoutException) return "timeout";
        if (error instanceof NotifierUnavailableException) return "unavailable";
        return "other";
    }
}
