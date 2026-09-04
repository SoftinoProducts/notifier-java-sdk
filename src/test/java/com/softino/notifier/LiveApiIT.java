package com.softino.notifier;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

/**
 * Live end-to-end smoke test against a real Notifier instance.
 *
 * <p>This is intentionally NOT run by the normal {@code mvn test} (it is named {@code *IT},
 * the Maven Failsafe convention). It only runs when {@code NOTIFIER_API_KEY} and (optionally)
 * {@code NOTIFIER_BASE_URL} are set, e.g.:</p>
 *
 * <pre>
 * NOTIFIER_API_KEY=... mvn -DskipTests=false -Dtest=LiveApiIT -pl . test
 * </pre>
 *
 * <p>It performs one real send + status poll to confirm the contract end to end.</p>
 */
public class LiveApiIT {

    private NotifierApi api;

    @Before
    public void requireKey() {
        String key = System.getenv("NOTIFIER_API_KEY");
        Assume.assumeTrue("NOTIFIER_API_KEY not set; skipping live IT", key != null && !key.isEmpty());
        String baseUrl = System.getenv("NOTIFIER_BASE_URL");
        api = baseUrl == null || baseUrl.isEmpty()
                ? new NotifierApi(key)
                : new NotifierApi(key, baseUrl);
    }

    @Test
    public void sendAndPollStatus() {
        String recipient = System.getenv("NOTIFIER_TEST_RECIPIENT");
        if (recipient == null || recipient.isEmpty()) {
            recipient = "+989120000000";
        }
        SendResult r = api.send(ChannelType.SMS, recipient, Content.of("Softino SDK live smoke test"));
        org.junit.Assert.assertNotNull("expected a notification id", r.getId());

        // Poll briefly for the coarse status to leave 'queued' (delivery is async).
        StatusResult s = null;
        for (int i = 0; i < 10; i++) {
            s = api.status(r.getId());
            if (s != null && !"queued".equalsIgnoreCase(s.getStatus())) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.junit.Assert.assertNotNull("status result should not be null", s);
        System.out.println("Live IT result: status=" + s.getStatus() + " provider=" + s.getProvider());
    }

    @Test
    public void sendTemplateSmoke() {
        String template = System.getenv("NOTIFIER_TEST_TEMPLATE_ID");
        Assume.assumeTrue("NOTIFIER_TEST_TEMPLATE_ID not set; skipping", template != null && !template.isEmpty());
        String recipient = System.getenv("NOTIFIER_TEST_RECIPIENT");
        if (recipient == null || recipient.isEmpty()) {
            recipient = "+989120000000";
        }
        SendResult r = api.sendTemplate(ChannelType.SMS, recipient, template,
                Collections.singletonMap("token", "123456"));
        org.junit.Assert.assertNotNull(r.getId());
    }
}
