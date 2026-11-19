package com.softino.notifier;

import com.softino.notifier.metrics.MicrometerSendListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Verifies the optional Micrometer adapter publishes the documented metric names and tags. */
public class MicrometerMetricsTest {

    private TestServer hub;
    private SimpleMeterRegistry registry;

    @Before
    public void start() throws Exception {
        hub = new TestServer(exchange -> TestServer.respondJson(exchange, 202,
                "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"queued\",\"channel_type\":\"sms\"}"));
        registry = new SimpleMeterRegistry();
    }

    @After
    public void stop() {
        if (hub != null) hub.close();
        if (registry != null) registry.close();
    }

    @Test
    public void publishesTimersCountersAndGauges() throws Exception {
        final AtomicReference<NotifierApi> apiRef = new AtomicReference<NotifierApi>();
        NotifierConfig config = NotifierConfig.builder("test-key")
                .baseUrl(hub.baseUrl())
                .closeDrainMillis(100)
                .maxRetries(0)
                .sendListener(new MicrometerSendListener(registry,
                        () -> apiRef.get() == null ? null : apiRef.get().stats()))
                .build();
        NotifierApi api = new NotifierApi(config);
        apiRef.set(api);
        try {
            api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                    .get(3, TimeUnit.SECONDS);
            assertTrue(api.awaitIdle(java.time.Duration.ofSeconds(3)));

            Timer timer = registry.find("notifier.send.duration")
                    .tags("outcome", "success", "priority", "bulk")
                    .timer();
            assertNotNull("success timer must be registered", timer);
            assertTrue(timer.count() >= 1);

            Gauge queueDepth = registry.find("notifier.queue.depth").tags("priority", "high").gauge();
            assertNotNull("queue depth gauge must be registered", queueDepth);
            assertEquals(0.0, queueDepth.value(), 0.001);

            Gauge inFlight = registry.find("notifier.send.in_flight").gauge();
            assertNotNull("in-flight gauge must be registered", inFlight);
            assertEquals(0.0, inFlight.value(), 0.001);

            // Micrometer keeps a weak reference to a gauge's state object. The listener must
            // keep the supplier alive, otherwise a GC turns both gauges into NaN.
            for (int i = 0; i < 3; i++) {
                System.gc();
                Thread.sleep(50);
            }
            assertFalse("queue gauge went NaN after GC", Double.isNaN(queueDepth.value()));
            assertFalse("in-flight gauge went NaN after GC", Double.isNaN(inFlight.value()));

            hub.handler(exchange -> TestServer.respondJson(exchange, 500, "{\"error\":\"boom\"}"));
            api.sendTemplateByNameAndForget(ChannelType.SMS, "+989120000000", "otp", "123456");
            assertTrue(api.awaitIdle(java.time.Duration.ofSeconds(3)));

            Counter dropped = registry.find("notifier.send.dropped").tags("priority", "bulk").counter();
            assertNotNull("dropped counter must be registered", dropped);
            assertEquals(1.0, dropped.count(), 0.001);

            Timer failure = registry.find("notifier.send.duration")
                    .tags("outcome", "dropped", "priority", "bulk")
                    .timer();
            assertNotNull("dropped sends still record a duration", failure);
        } finally {
            api.close();
        }
    }
}
