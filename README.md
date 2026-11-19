# Notifier Java SDK

[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://adoptium.net/)

A Java client for the **Notifier** multi-channel notification hub — built as a **drop-in replacement for
[`kavenegar-java`](https://github.com/kavenegar/kavenegar-java)**. Kavenegar **template sends** map
directly to Notifier's **send-with-template-by-name**, so migrating is mostly a package rename.

```java
// Before (kavenegar-java):   import com.kavenegar.sdk.KavenegarApi;
// After  (notifier):          import com.softino.notifier.kavenegar.KavenegarApi;
KavenegarApi api = new KavenegarApi("YOUR-KEY");
api.verifyLookup("+989120000000", "123456", "verify");   // template by name — unchanged
```

---

## Installation (JitPack)

Build from a GitHub tag:

```xml
<repositories>
    <repository><id>jitpack.io</id><url>https://jitpack.io</url></repository>
</repositories>
<dependency>
    <groupId>com.github.SoftinoProducts</groupId>
    <artifactId>notifier-java-sdk</artifactId>
    <version>1.2.0</version>
</dependency>
```

Gradle:

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.SoftinoProducts:notifier-java-sdk:1.2.0' }
```

Requires **Java 8+** and a Notifier API key (optionally a custom base URL — see
[Configuration](#configuration)). The async transport uses Apache HttpAsyncClient 4.x;
Micrometer is an *optional* dependency, needed only for `MicrometerSendListener`.

---

## Quick start

`NotifierApi` is the channel-agnostic client. The recommended way to send is
**send-with-template-by-name**, with the values passed positionally:

```java
import com.softino.notifier.*;

NotifierApi api = new NotifierApi("YOUR-API-KEY");

// template "verify" declares param_names ["token"]; values bind in order.
SendResult r = api.sendTemplateByName(ChannelType.SMS, "+989120000000", "verify", "123456");

System.out.println(r.getId());               // Notifier UUID
System.out.println(r.getStatus());           // e.g. "queued"

// Poll delivery status
StatusResult s = api.status(r.getId());
if (s.isDelivered()) { /* delivered */ }
```

`NotifierApi` is `AutoCloseable` — call `close()` when done. It drains accepted asynchronous
sends for a configurable window, then releases the connection pool and all SDK threads.

---

## Non-blocking sends (1.2+)

The original methods (`send`, `sendTemplateByName`, `verifyLookup`, ...) are **blocking**: they
return after the hub answers. That is unchanged and still supported. Since 1.2 every send has
two non-blocking equivalents:

- `*Async(...)` returns a `CompletableFuture<...>` as soon as the send is queued.
- `*AndForget(...)` returns `void` immediately and exposes no result.

Neither does network I/O on the calling thread. A request-queue or sale endpoint can therefore
send an OTP without paying a hub round trip (or a 10–30 second socket timeout when the hub is
unhealthy).

```java
// Before: the caller's request thread waits for the hub.
SendResult result = api.verifyLookup(phone, otp, "vibe:salerequest");
String notificationId = result.getNotificationId();

// After: the caller only enqueues; the hub round trip happens on an SDK daemon thread.
String key = "otp:" + saleUuid;
api.verifyLookupAndForget(phone, otp, "vibe:salerequest",
        SendOptions.builder()
                .idempotencyKey(key)
                .traceId(saleUuid)
                .priority(SendPriority.HIGH)     // OTP lane
                .deadline(Duration.ofSeconds(5))
                .build());
```

### API map

Every blocking send shape has an async and a fire-and-forget form:

| Blocking (unchanged) | Non-blocking future | Fire-and-forget |
|---|---|---|
| `send(type, recipient, Content/String[, SendOptions])` | `sendAsync(...)` | `sendAndForget(...)` |
| `sendTemplate(type, recipient, templateId, ...)` | `sendTemplateAsync(...)` | `sendTemplateAndForget(...)` |
| `sendTemplateByName(type, recipient, name, ...)` | `sendTemplateByNameAsync(...)` | `sendTemplateByNameAndForget(...)` |
| `bulk(type, messages[, simulated])` | `bulkAsync(type, messages[, simulated[, SendOptions]])` | `bulkAndForget(...)` |
| `status(id)` / `statusByProviderMessageId(id)` | `statusAsync(id)`, `statusByNotificationIdAsync(id)`, `statusByProviderMessageIdAsync(id)` | — |
| `KavenegarApi.verifyLookup(receptor, token, template, ...)` | `verifyLookupAsync(...)` | `verifyLookupAndForget(...)` |
| `KavenegarApi.send(sender, receptor, message)` | `sendAsync(...)` | `sendAndForget(...)` |
| `KavenegarApi.sendArray(sender, receptors, messages)` | `sendArrayAsync(...)` | `sendArrayAndForget(...)` |
| `sendToDevice(deviceId, subject, body)` | `sendToDeviceAsync(deviceId, subject, body[, SendOptions])` | `sendToDeviceAndForget(...)` |
| `sendTemplateToDevice(deviceId, name, params...)` | `sendTemplateToDeviceAsync(deviceId, name[, SendOptions], params...)` | `sendTemplateToDeviceAndForget(...)` |

The `verifyLookup` async forms also cover the named-parameter (`List<PairValue>`) and
`simulated` overloads. Legacy overloads whose only extra arguments are Kavenegar's
`MessageType`, date or `localId` already ignored those fields; their async equivalent is the
canonical `sendAsync`/`sendArrayAsync` call.

```java
// Async: get the hub-accepted notification id without blocking the request thread.
SendOptions options = SendOptions.builder()
        .idempotencyKey("otp:" + saleUuid)
        .traceId(saleUuid)
        .priority(SendPriority.HIGH)                 // OTP lane, never behind bulk
        .deadline(Duration.ofSeconds(5))             // total: queue + connect + read + retries
        .build();

CompletableFuture<com.softino.notifier.SendResult> future =
        api.sendTemplateByNameAsync(ChannelType.SMS, phone, "verify", options, "123456");

future.thenAccept(sent -> {
            // Runs on an SDK thread: hand a DB write to your own executor, never block here.
            myExecutor.execute(() -> repo.saveNotificationId(saleUuid, sent.getId()));
        })
        .exceptionally(error -> {
            log.warn("otp enqueue failed key={} error={}", options.getIdempotencyKey(), error.toString());
            return null;
        });
```

### Migration steps (blocking → non-blocking)

1. **Bump the dependency to `1.2.0`.** No source change is required for existing blocking
   calls; they keep their signatures, timeouts and exception types.
2. **Start with the latency-critical call site** (OTP on `/sale/request` or
   `/register/request`). Replace `verifyLookup(...)` with `verifyLookupAndForget(...)` and pass
   an idempotency key, trace id, `SendPriority.HIGH` and a deadline. The caller now only
   enqueues.
3. **Decide how to handle the result.** `*AndForget` returns `void` and throws only
   `NotifierQueueFullException` (queue full, nothing sent, wait `retryAfterMillis`) and
   `NotifierClosedException` (client closed). If you need `notificationId` in your own
   transaction, use `*Async` and persist in a callback on your executor (example above); if you
   only need correlation, keep the idempotency key.
4. **Configure the client once.** Use `NotifierConfig` for queue sizes, deadline, retries,
   close drain, metrics/listener and `hubSupportsIdempotencyKey(...)`. Leave the idempotency
   flag `false` until the hub de-duplicates on `Idempotency-Key`; with it off the SDK never
   retries ambiguous failures.
5. **Observe failure instead of catching it.** Wire `api.stats()` or `SendListener` /
   `MicrometerSendListener`; a dropped fire-and-forget send is counted, not silent. Poll with
   `statusByNotificationIdAsync(...)` when you need delivery status.
6. **Shut down cleanly.** Call `api.close()` (or enable `registerShutdownHook(true)`); it
   rejects new sends, drains accepted work for `closeDrainMillis`, then fails the rest and
   stops every SDK thread.
7. **Roll out call site by call site.** Blocking and non-blocking methods can coexist; revert a
   call site by switching it back without changing the client.

A longer before/after guide is in [MIGRATION.md](MIGRATION.md); the complete change list is
[CHANGELOG.md](CHANGELOG.md). Failure and performance evidence is in
[FAILURE-TESTS.md](FAILURE-TESTS.md) and [PERFORMANCE.md](PERFORMANCE.md).

> **What does *not* change:** delivery to the handset. Notifier, the provider and the operator
> still decide when an SMS arrives. This release removes the caller-side wait and thread
> occupancy; it does not shorten delivery time and cannot promise a "zero-latency" code.

### Delivery guarantee matrix

| Method | Caller blocks | Guarantee |
|---|---|---|
| `send*` (blocking, unchanged) | Until the hub replies (up to the socket timeout) | Hub acceptance; failures are thrown (`ApiException` / `HttpException`). |
| `send*Async` | No | Future completes after hub acceptance; failures complete it exceptionally with a typed error. |
| `send*AndForget` | No | At-most-once. If the hub fails, the message may be lost; it is counted in `notifier.send.dropped` / `stats().getDropped()`. |
| `send*Async` + `idempotencyKey` | No | At-least-once from the SDK's point of view; duplicate-resistant in the hub **only once the hub honours `Idempotency-Key`** (see Hub coordination below). |

### Priority lanes and backpressure

Two independent lanes exist, each with its own bounded queue, dispatcher threads and HTTP
concurrency quota:

| Lane | Default use | Queue default | In-flight default |
|---|---|---:|---:|
| `HIGH` | OTP / transactional (`verifyLookup*` defaults here) | 2,000 | 8 |
| `BULK` | everything else (`send*` defaults here) | 10,000 | 24 |

Bulk traffic can never consume the HIGH lane's queue or permits. The SDK never batches a
`HIGH` message.

When a lane is full, the SDK refuses locally and immediately:

- `send*AndForget` throws `NotifierQueueFullException` (with `retryAfterMillis`).
- `send*Async` returns a future completed exceptionally with the same type.

The caller thread is never blocked to make room, and a message is never dropped silently. The
same applies after `close()`, which yields `NotifierClosedException`.

### Deadlines, retries and idempotency

A send has one **total deadline** covering queueing, connect, write, read and every retry. The
default is 10 seconds; override per send with `SendOptions.deadline(...)`. An expired deadline yields
`NotifierTimeoutException` (retryable), never a longer wait.

Per-attempt defaults for async sends are connect 2s and read 5s (configurable on
`NotifierConfig` and, per send, with `connectTimeout` / `readTimeout`). The read timeout is
enforced on the NIO reactor's 100ms tick, so expect up to roughly that much overshoot.

Retries use exponential backoff with full jitter on a scheduler; there is no `Thread.sleep`
anywhere. The default cap is 2 retries (three attempts). Safety rules:

| Failure | Retried by default? | Why |
|---|---|---|
| DNS failure, connection refused, connect timeout, TLS handshake | Yes | The request never reached the hub. |
| HTTP 429 | Yes, honouring `Retry-After` | The hub rejected it without processing. |
| Read timeout, connection reset after write, HTTP 5xx | **No** | The hub may have accepted the message. Enable only with idempotency support. |
| HTTP 4xx (except 429) | No | Retrying the same payload fails the same way. |

Set `hubSupportsIdempotencyKey(true)` only after the hub de-duplicates `POST /v1/notifications`
on `Idempotency-Key`; then the SDK retries ambiguous failures with the **same** key on every
attempt. When the caller does not supply a key, the SDK generates one and returns it from
`SendResult.getIdempotencyKey()` so the result can be correlated with the hub's record.

```java
NotifierConfig config = NotifierConfig.builder("KEY")
        .hubSupportsIdempotencyKey(true)   // only after the hub ships support
        .maxRetries(2)
        .build();
```

### Lifecycle and shutdown

```java
NotifierApi api = new NotifierApi(NotifierConfig.builder("KEY")
        .closeDrainMillis(5_000)
        .registerShutdownHook(true)   // optional
        .build());

try {
    api.verifyLookupAndForget(phone, otp, "vibe:salerequest");
} finally {
    api.close();   // rejects new sends, drains accepted work, fails the rest, stops all threads
}
```

`close()` is idempotent. Every accepted future is settled: it completes successfully, or fails
with a typed exception. Work still queued or in flight when the drain window ends is failed
with `NotifierClosedException`. No SDK thread or connection survives `close()`.

All SDK threads are daemons named `notifier-sender-high-*`, `notifier-sender-bulk-*`,
`notifier-reactor-*`, `notifier-retry-*`, or `notifier-deadline-*`. Their counts are fixed by
configuration and do not grow with send rate. With the defaults the steady-state inventory is
about seven: two bulk dispatchers, one HIGH dispatcher, two reactor threads (one worker plus
Apache's dispatch thread), one retry scheduler and one deadline watchdog.

### Observability

Metrics (via the optional `MicrometerSendListener`, or a custom `SendListener`):

| Metric | Type | Tags |
|---|---|---|
| `notifier.send.duration` | timer | `outcome`, `priority` |
| `notifier.queue.depth` | gauge | `priority` |
| `notifier.send.in_flight` | gauge | — |
| `notifier.send.retries` | counter | `reason`, `priority` |
| `notifier.send.rejected` | counter | `reason`, `priority` |
| `notifier.send.dropped` | counter | `priority` |

```java
NotifierApi api = new NotifierApi(NotifierConfig.builder("KEY")
        .sendListener(new MicrometerSendListener(registry, () -> apiStats.get().stats()))
        .build());
```

The send counters exclude status reads (a status lookup uses the same engine but is not a
send). Without Micrometer, implement `SendListener` directly or read `api.stats()`. Logging uses
`java.util.logging` under the `com.softino.notifier` logger; it records identifiers, priority,
outcome and duration only — never the body, OTP, token or full recipient number. `traceId`
from `SendOptions` is copied to the `X-Trace-Id` header and into the SLF4J MDC around listener
callbacks when SLF4J is present; the SDK's own log lines also carry it as an explicit field.

### Hub coordination (required for the full P1 feature set)

Three things cannot be delivered by the SDK alone:

1. `Idempotency-Key` support on `POST /v1/notifications` (otherwise retries stay restricted to
   failures before the request could reach the hub).
2. An `X-Priority` header (sent as `high`/`bulk`) and a separate OTP lane inside the hub queue.
3. A batch endpoint for `BULK` messages; `HIGH` messages are never batched.

Until 1 and 2 are implemented hub-side, P0 (non-blocking caller path, bounded queues, deadlines,
shutdown) works independently and is safe to deploy.

### Configuration defaults

| Setting | Default | Builder |
|---|---:|---|
| Async connect timeout | 2s | `asyncConnectTimeoutMillis` |
| Async read timeout | 5s | `asyncReadTimeoutMillis` |
| Total send deadline | 10s | `defaultDeadlineMillis` |
| Retries | 2 | `maxRetries` |
| Retry base / cap | 200ms / 2s | `retryBaseDelayMillis` / `retryMaxDelayMillis` |
| 429 fallback when `Retry-After` is absent | 1s | `rateLimitedRetryAfterMillis` |
| HIGH queue / threads / in-flight | 2,000 / 1 / 8 | `highPriorityQueueCapacity` / `highPrioritySenderThreads` / `highPriorityMaxInFlight` |
| BULK queue / threads / in-flight | 10,000 / 2 / 24 | `bulkQueueCapacity` / `bulkPrioritySenderThreads` / `bulkMaxInFlight` |
| NIO reactor worker threads / tick | 1 (plus 1 dispatch thread) / 100ms | `ioThreads` / `ioSelectIntervalMillis` |
| Close drain | 5s | `closeDrainMillis` |
| Blocking connect / read (legacy) | 10s / 30s | `blockingConnectTimeoutMillis` / `blockingReadTimeoutMillis` |

The blocking defaults are unchanged from 1.1.x. A client built with the old constructors keeps
its old blocking timeouts and gets the async defaults above.

---

## Sending with a template (by name)

The backend looks up the template by `(channel, name, locale)`, renders it, and dispatches.

> **Prerequisite:** the template must declare its `param_names` (in the Notifier UI/API) in the order
> you pass values. If you'd rather reference placeholders by name, use the map form.

### Values positionally (recommended)

```java
api.sendTemplateByName(ChannelType.SMS, phone, "verify", "123456");              // params: [token]
api.sendTemplateByName(ChannelType.SMS, phone, "order_notice", "50000", "Tehran"); // params: [amount, region]
```

With extra options (`SendOptions` comes *before* the values):

```java
api.sendTemplateByName(ChannelType.SMS, phone, "order_confirmed",
        SendOptions.builder().locale("fa").callbackUrl("https://your-app/cb").build(),
        "12345");
```

### Values by name (map)

```java
api.sendTemplateByName(ChannelType.SMS, phone, "verify", Collections.singletonMap("token", otp));
```

### Locale

The default locale is `fa`. Override with `.locale(...)`:

```java
api.sendTemplateByName(ChannelType.SMS, phone, "login_otp", SendOptions.builder().locale("en").build(), "123456");
```

### Route through a channel group: `"group:template"`

Prefix the template name with a group name (separated by `:`) to route via a channel group
**by name** — the part before `:` is the group name, the part after is the template name. This is
supported by **every** send-with-template-by-name overload (and in the Kavenegar facade).

Core client:

```java
api.sendTemplateByName(ChannelType.SMS, phone, "sms-group:verify", "123456");
```

Equivalently, set the group explicitly via `SendOptions` (useful when the group name is dynamic):

```java
api.sendTemplateByName(ChannelType.SMS, phone, "verify",
        SendOptions.builder().groupName("sms-group").build(), "123456");
```

Kavenegar facade (same shorthand):

```java
api.verifyLookup(phone, "123456", "sms-group:verify");
```

Both send `group_name: "sms-group"` + `template_name: "verify"`.

---

## Migrating from kavenegar-java

### The facade

Keep your code and swap the import to `com.softino.notifier.kavenegar.KavenegarApi`. It returns the
facade's `models.*` / `enums.*` / `excepctions.*` types (under `com.softino.notifier.kavenegar`), so
status/enum handling is unchanged.

```java
import com.softino.notifier.kavenegar.KavenegarApi;
import com.softino.notifier.kavenegar.models.*;
import com.softino.notifier.kavenegar.enums.*;

KavenegarApi api = new KavenegarApi("YOUR-KEY");

// Template send (by name) — unchanged
SendResult r = api.verifyLookup("+989120000000", "123456", "verify");
String message = r.getMessage();    // rendered text
long   msgId   = r.getMessageId();  // provider message id (Long)

StatusResult s = api.status(msgId);
MessageStatus ms = s.getStatus();   // enum (e.g. Delivered)
String text = s.getStatusText();    // mapped statusText

api.send("100085902", "+989120000000", "hello");  // plain (non-template)
```

> **Polling a message you just sent — use the UUID, not the provider message id.**
> Notifier is **async**: `send`/`verifyLookup` return before the provider has been reached, so
> `r.getMessageId()` is `0` and `status(msgId)` throws `ApiException: not found` for a freshly
> sent message. The notifier UUID is always present (`r.getNotificationId()`), and
> `statusByNotificationId(uuid)` resolves reliably.
>
> **Before — breaks for a fresh send:**
> ```java
> SendResult r = api.verifyLookup("+989120000000", "123456", "vibe:salerequest");
> long msgId = r.getMessageId();        // 0 — no provider message id yet (async Notifier)
> StatusResult s = api.status(msgId);   // ApiException: not found
> ```
>
> **After — reliable:**
> ```java
> SendResult r = api.verifyLookup("+989120000000", "123456", "vibe:salerequest");
> String uuid      = r.getNotificationId();            // UUID, always returned at send time
> StatusResult s   = api.statusByNotificationId(uuid); // -> GET /v1/notifications/{uuid}
> MessageStatus ms = s.getStatus();                    // e.g. Delivered
> ```
>
> `status(messageId)` (by provider message id) still works once the provider id has been
> logged/recorded, but `statusByNotificationId(uuid)` is the safe choice for a just-sent message.

> **Template params are positional.** `verifyLookup(receptor, token, token2, token3, template)` sends its
> values as an ordered `template_params` array, so the template must declare `param_names` in the same
> order (`["token"]`, `["token","token2","token3"]`, …). If the template uses different placeholder
> names, use the map form `sendTemplateByName(channel, phone, name, vars)` instead of `verifyLookup`.

### Method mapping

| Kavenegar (`com.kavenegar.sdk.*`) | Notifier facade (`com.softino.notifier.kavenegar`) |
|---|---|
| `verifyLookup(receptor, token, template)` | `verifyLookup(receptor, token, template)` |
| `send(sender, receptor, message)` | `send(sender, receptor, message)` |
| `status(messageId /* long */)` | `status(messageId /* long */)` |
| `statusLocalMessageId(localId)` | `statusLocalMessageId(localId)` |
| `sendArray(...)`, `countOutbox(...)` / `countInbox(...)`, `accountInfo()` | same signatures |

Methods that map to features Notifier doesn't implement still return the kavenegar type but throw a
`kavenegar.excepctions.BaseException`, so existing `catch (BaseException)` keeps working.

The `verifyLookup` overloads take an optional trailing `Boolean simulated`. Passing `true` rehearses
the send (the provider is never contacted, the notification is reported delivered by `simulator`)
while the template, group and channel stay exactly as they are — see
[Rehearsing a send](#rehearsing-a-send-simulated).

---

## Rehearsing a send (simulated)

`SendOptions.simulated(true)` runs the whole pipeline — routing, group selection, template
rendering, provider-template mapping, the status event and the panel — without delivering
anything and without touching your templates, groups or channels. The service swaps the provider
call for a simulator, so the notification is reported as delivered by provider `simulator` with a
provider message id like `sim-sms-kavenegar-1`: the would-be provider is still named.

```java
SendResult r = api.sendTemplateByName(ChannelType.SMS, phone, "sms-group:verify",
        SendOptions.builder().simulated(true).build(),   // your real template and group, unchanged
        "123456");

System.out.println(r.isSimulated());   // true
System.out.println(r.getProvider());   // simulator
```

The Kavenegar facade takes the same flag per call, so an existing call site can be put into
rehearsal by adding one argument — no migration to `NotifierApi`:

```java
KavenegarApi api = new KavenegarApi("YOUR-KEY");

api.verifyLookup("+989120000000", "123456", "sms-group:verify", true);      // rehearsed
api.verifyLookup("+989120000000", "1", "2", "3", "order_notice", true);     // with token2/token3
api.verifyLookup("+989120000000", "", "", "", "verify", params, true);      // with named params

api.verifyLookup("+989120000000", "123456", "sms-group:verify");            // unchanged: really sent
```

In a bulk send the flag applies to the whole batch and a single message can opt out:

```java
api.bulk(ChannelType.SMS, Arrays.asList(
        RecipientMessage.of(phoneA, Content.of("rehearsed")),
        RecipientMessage.builder().recipient(phoneB).content(Content.of("really sent"))
                .simulated(false).build()),
        true);   // batch-level simulated
```

Worth knowing: a rehearsal still creates a notification — it is counted in analytics and visible
in the panel with `provider = simulator`. It is not a dry run that leaves no trace.
`SendResult.isSimulated()` reports whether the delivery was rehearsed, as the service recorded it.

---

## Status lookup

```java
StatusResult byId = api.status(r.getId());                 // by Notifier UUID
if (byId.isDelivered()) { /* delivered */ }
else if (byId.isFailed()) { /* handle failure */ }
else if (byId.isQueued()) { /* in flight — poll again */ }

StatusResult byProvider = api.statusByProviderMessageId(r.getProviderMessageId()); // by provider id
```

---

## Receiving callbacks

When a send carries a `callback_url`, Notifier POSTs a status event to it. The request is
signed so a receiver can tell a genuine callback from a forged one:

| Header | Value |
| --- | --- |
| `X-Notifier-Signature` | `sha256=<hex>` |
| `X-Notifier-Timestamp` | Unix **seconds** |

The signed material is `"<timestamp>.<raw body>"`:

```text
sha256=<lowercase hex>( HMAC-SHA256(webhook_secret, X-Notifier-Timestamp + "." + raw_body) )
```

The digest covers the exact body bytes, so verify **before** parsing the JSON: decoding and
re-encoding changes whitespace and field order and the signature will never match. A digest
over the body alone is the old, broken scheme and is refused.

```java
private static final CallbackVerifier VERIFIER =
        CallbackVerifier.forSecret(System.getenv("NOTIFIER_WEBHOOK_SECRET"));

protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
    byte[] raw = readAll(req.getInputStream());   // raw bytes, before any JSON parsing
    try {
        VERIFIER.verifyOrThrow(raw,
                req.getHeader(CallbackVerifier.TIMESTAMP_HEADER),
                req.getHeader(CallbackVerifier.SIGNATURE_HEADER));
    } catch (CallbackSignatureException e) {
        // e.getReason() is MISSING_TIMESTAMP, STALE_TIMESTAMP, INVALID_SIGNATURE or MISSING_BODY
        res.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
        return;
    }

    CallbackEvent event = CallbackEvent.fromJson(new String(raw, StandardCharsets.UTF_8));
    if (event.isDelivered()) {
        // event.getNotificationId(), event.getChannelType(), event.getFanoutId()
    }
    res.setStatus(HttpServletResponse.SC_NO_CONTENT);
}
```

A callback older than the tolerance window (300 seconds by default, `withTimestampTolerance`)
is refused even though its digest is valid: otherwise a captured request could be replayed
forever. The verifier compares in constant time and is safe for concurrent use.

A fixed vector you can test an implementation with, in any language:

```text
secret    = whsec_test_secret
timestamp = 1730000000
body      = {"notification_id":"801e8e60-4686-4cba-8068-e36c88618b16","status":"delivered","channel_type":"telegram"}

signature = 79c471e9051eb5cb40beba0463903244d673cd74a0361ab9114daece83c38114
body-only = 6e3cc01bb604f7cd43b6a4b6ec86456ec0853155581b3f70a03e2d9ccc6c3ba6   (must be refused)
```

The service retries a callback on any non-2xx response: three attempts, 500 ms then 1 s
apart, inside a 10 s timeout. Answer `204` once the event is accepted.

---

## Web Push devices

A Web Push recipient is a **device id**, not the browser's subscription. Register the browser's
subscription once, then send to the id it returns:

```java
// The frontend calls registration.pushManager.subscribe({ applicationServerKey, userVisibleOnly: true })
// and posts endpoint + keys.p256dh + keys.auth to your backend.
VapidKey vapid = api.getVapidPublicKey();   // pass vapid.getPublicKey() to the browser as applicationServerKey

PushDevice device = api.registerPushDevice(
        PushSubscription.of(endpoint, p256dh, auth), "user-42");

api.sendToDevice(device.getId(), "Order ready", "Your order has shipped.");
api.sendTemplateToDevice(device.getId(), "order-ready", "12345");

api.deletePushDevice(device.getId());       // logout: stop sending to this browser
```

Registering the same subscription again updates the same device and returns the same id, so a
frontend may call it on every visit. `api.listPushDevices("user-42", 100, 0)` returns one user's
devices, newest first; each item's `isUsable()` is false once the push service has reported the
subscription gone. `NotifierApi.deviceRecipient(deviceId)` returns the device id to use with the
generic `send(...)` path, and rejects a subscription JSON or an `https://` endpoint — passing one
of those as a recipient can never work.

---

## Error handling

| Exception | Meaning | `isRetryable()` |
|---|---|---|---|
| `NotifierException` | Base type for SDK errors. | `false` |
| `NotifierUnavailableException` | Hub unreachable or 5xx. | `true` |
| `NotifierTimeoutException` | Total deadline or read timeout elapsed. | `true` |
| `NotifierRateLimitedException` | HTTP 429; carries `retryAfterMillis`. | `true` |
| `NotifierInvalidRequestException` | 4xx or locally invalid request. | `false` |
| `NotifierQueueFullException` | Local queue full; carries `retryAfterMillis`. | `true` |
| `NotifierClosedException` | Client closed. | `false` |
| `ApiException` | Blocking path business/API error. Carries a `code`. | `false` |
| `HttpException` | Blocking path transport/HTTP failure. Carries a `code`. | 408/429/5xx |

```java
try {
    api.sendTemplateByName(ChannelType.SMS, recipient, "verify", "123456");
} catch (ApiException e) {   // business error — e.getCode()
} catch (HttpException e) {  // network/HTTP error — e.getCode()
}
```

---

## Channels

`ChannelType` exposes constants for all supported channels:

| Constant | Value | Notes |
|---|---|---|
| `ChannelType.SMS` | `sms` | Kavenegar, SMS.ir, … |
| `ChannelType.EMAIL` | `email` | SMTP providers |
| `ChannelType.WEBPUSH` | `webpush` | Centrifugo |
| `ChannelType.TELEGRAM` | `telegram` | |
| `ChannelType.SLACK` | `slack` | |
| `ChannelType.BALE` | `bale` | Safir / Bale messenger |

### Telegram and Bale

Both are ordinary channels: create a template with `channel_type: "telegram"` or
`"bale"` and declare its `param_names`, exactly as for SMS. For a **direct** send the
recipient is the messenger chat id:

```java
// Direct Telegram: recipient is the numeric chat id.
api.telegram().send("67313260", "Your verification code is 123456");
api.sendTemplateByName(ChannelType.TELEGRAM, "67313260", "telegram-otp", "123456");

// Direct Bale: recipient is the Bale chat id.
api.bale().send("123456789", "Your verification code is 123456");
api.sendTemplateByName(ChannelType.BALE, "123456789", "bale-otp", "123456");
```

Prefer `sendTemplateByName`: the template is rendered and localized server-side, and the
values bind positionally to the template's `param_names`. `api.telegram()` and
`api.bale()` are typed conveniences over the same core send path; they are optional.

A direct Telegram send needs a chat id and does **not** use a binding. A route that sends
to a phone number can resolve the phone to a Telegram chat through a binding; that is the
`recipient_source: "binding"` mode described below. Bale has no binding step: use the Bale
chat id.

All channels use the same `sendTemplateByName`/`send` calls. Use `ChannelType.of("whatsapp")` for a
channel you define yourself.

### Saved routes: `"route:name"` (fan-out)

A saved route is a named fan-out defined in Notifier (UI or
`POST /v1/notification-routes`). It is not a channel group:

| | Channel group | Saved route |
|---|---|---|
| Purpose | failover inside one channel type | deliver over every selected target |
| Targets | channels of one type | up to five targets, possibly different types |
| Result | one notification | one child notification per target, grouped by `fanout_id` |
| SDK syntax | `"group:template"` | `"route:<name>"` |
| Bulk | allowed | rejected (`route:` is per-recipient) |

The route owns its channels, templates, recipient sources and required flags. The SDK
only names it:

```java
SendResult r = api.sendTemplateByName(ChannelType.SMS, phone, "route:otp", "123456");
```

`ChannelType.SMS` is ignored for a route: the route defines every channel. A `route:`
send must not also set `template_name`, `group_name` or `channel_id`; the SDK rejects that
combination before calling the API.

Make retries safe with an idempotency key. Replaying the same key returns the original
fanout instead of sending the targets again:

```java
SendResult r = api.sendTemplateByName(ChannelType.SMS, phone, "route:otp",
        SendOptions.builder().idempotencyKey("otp-" + requestId).build(),
        "123456");
```

For code that builds options dynamically, use `send` (not `sendTemplateByName`) so no
`template_name` is added:

```java
api.send(ChannelType.SMS, phone, Content.of(""),
        SendOptions.builder()
                .routeName("otp")
                .templateParam("123456")
                .build());
```

A target can instead let the request choose the template. Set
`template_source: "request"` and omit its template; the send supplies `template_name` (or
`template_id`), resolved for that target's channel type. One generic Telegram route can
then serve any notification category:

```json
{
  "name": "telegram-generic",
  "targets": [
    {"channel_type": "telegram", "channel_id": "TELEGRAM_CHANNEL_ID", "template_source": "request", "recipient_source": "binding", "required": false}
  ]
}
```

```java
api.sendTemplateByName(ChannelType.TELEGRAM, phone, "otp-login",
        SendOptions.builder().routeName("telegram-generic").build(),
        "123456");
```

`ChannelType.TELEGRAM` is ignored; the route decides the channel. A send through a route
without a `template_source: "request"` target rejects a request template with `400`; a
request-template target without one is also `400`.

A route definition created once in the UI or with the API looks like this:

```json
{
  "name": "otp",
  "targets": [
    {"channel_type": "sms", "channel_id": "SMS_CHANNEL_ID", "template_id": "SMS_TEMPLATE_ID", "recipient_source": "request", "required": true},
    {"channel_type": "bale", "channel_id": "BALE_CHANNEL_ID", "template_id": "BALE_TEMPLATE_ID", "recipient_source": "request", "required": true},
    {"channel_type": "telegram", "channel_id": "TELEGRAM_CHANNEL_ID", "template_id": "TELEGRAM_TEMPLATE_ID", "recipient_source": "binding", "required": false}
  ]
}
```

- `recipient_source: "request"` sends to the route's `recipient` (the phone number).
- `recipient_source: "binding"` is for Telegram: the service resolves the phone to a
  chat id through `channel_bindings`.
- `required: true` makes a missing Telegram binding fail the send with
  `400 no telegram binding for <phone>`. With `required: false`, that leg is reported as
  `skipped` instead.
- The route and every target template must be active, and all templates must declare the
  same number of parameters (the API validates this; a route has at most five targets).

#### Binding a Telegram chat

Binding is an account-management operation, not a send; the SDKs do not wrap it. Create a
one-time deep link with the REST API:

```bash
curl -X POST "https://notifier-api.vibe.ir/v1/channels/$TELEGRAM_CHANNEL_ID/bindings" \
  -H "X-API-Key: $NOTIFIER_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"external_ref":"+989120000000"}'
```

The response contains `token`, `deep_link` and `expires_at`. Send the user to `deep_link`;
after they press **Start**, the phone is bound to that Telegram chat and a route send to
the phone resolves the Telegram leg automatically. The token is single-use and expires
after 15 minutes.

`GET /v1/channels/{channel_id}/bindings` returns a **bare JSON array** (no `items` envelope
and no pagination), newest first:

```json
[
  {
    "id": "8f0c0f6e-...",
    "channel_id": "53ea93ef-...",
    "external_ref": "+989120000000",
    "chat_id": "67313260",
    "created_at": "2026-10-27T11:00:00Z",
    "updated_at": "2026-10-27T11:00:00Z"
  }
]
```

Remove one by its binding `id`:

```bash
curl -X DELETE "https://notifier-api.vibe.ir/v1/channels/$TELEGRAM_CHANNEL_ID/bindings/$BINDING_ID" \
  -H "X-API-Key: $NOTIFIER_API_KEY"    # 204 No Content
```

An unknown binding id returns `404`; an invalid UUID returns `400`. Bindings are scoped to
the channel and tenant. One chat maps to one phone: a later `/start` for the same chat
rebinds it to the new phone and removes the old phone's binding.

#### What the route call returns

The call returns after the child legs are created; it does not wait for delivery:

```java
System.out.println(r.getId());           // primary notification UUID
System.out.println(r.getStatus());       // usually queued
System.out.println(r.getFanoutId());     // fanout UUID, null on a single-channel send
System.out.println(r.getFanoutStatus()); // queued | processing | delivered | failed | partial | skipped
```

Poll the primary leg with `api.status(r.getId())`. To inspect every child, call the fanout
endpoint (the SDKs do not wrap it):

```bash
curl -H "X-API-Key: $NOTIFIER_API_KEY" \
  "https://notifier-api.vibe.ir/v1/notification-fanouts/$FANOUT_ID"
```

`targets[]` reports each leg's `channel_type`, `recipient`, `status`, `notification_id` and
failure reason. The aggregate is `delivered` only when the required legs are delivered; an
optional missing binding is `skipped`, and a mix of delivered and failed legs is `partial`.
The route's children are independent notifications, so retrying one leg does not resend
the others.

Bulk sends (`api.bulk`) reject `route:` because a batch is scoped to one channel type.

### Also available

- **Bulk send** — `api.bulk(ChannelType.SMS, List<RecipientMessage>)`; each `RecipientMessage` supports a
  template by name (including the `group:template` shorthand) or a plain body.
- **History** — `api.listNotifications(HistoryQuery)` returns a paged list with a `next_cursor`.
- **Arbitrary body** — `api.send(ChannelType.SMS, recipient, Content.of("Hello"))` (no template).

---

## Configuration

The short constructors are kept for compatibility:

```java
NotifierApi api = new NotifierApi("YOUR-API-KEY");
NotifierApi api = new NotifierApi("YOUR-API-KEY", "https://notifier-api.internal");
// Blocking timeouts (connect, socket, ms) + max pooled connections per route
NotifierApi api = new NotifierApi("YOUR-API-KEY", "https://notifier-api.vibe.ir", 5_000, 15_000, 50);
```

Use `NotifierConfig` to tune the async path, queues, retries, listener and shutdown:

```java
NotifierConfig config = NotifierConfig.builder("YOUR-API-KEY")
        .baseUrl("https://notifier-api.vibe.ir")
        .defaultDeadlineMillis(10_000)
        .asyncConnectTimeoutMillis(2_000)
        .asyncReadTimeoutMillis(5_000)
        .highPriorityQueueCapacity(2_000)
        .bulkQueueCapacity(10_000)
        .maxRetries(2)
        .closeDrainMillis(5_000)
        .sendListener(listener)
        .build();
NotifierApi api = new NotifierApi(config);
```

See the defaults table in [Non-blocking sends](#non-blocking-sends-12).

---

## Building & publishing

```bash
mvn test      # unit + embedded-server contract tests
mvn package   # builds the jar

# Local before/after benchmark (not part of `mvn test`; CI reports it as an artifact too)
mvn -o -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
    -Dexec.mainClass=com.softino.notifier.AsyncBenchmark -Dexec.classpathScope=test
```

The non-blocking demo lives in [`demo/`](demo) (`./demo/run.sh`). Failure and performance
reports are in [FAILURE-TESTS.md](FAILURE-TESTS.md) and [PERFORMANCE.md](PERFORMANCE.md).

`LiveApiIT` is a live integration test (named `*IT`, excluded from `mvn test`):

```bash
NOTIFIER_API_KEY="..." NOTIFIER_BASE_URL="https://notifier-api.vibe.ir" mvn -Dtest=LiveApiIT test
```

JitPack builds from a GitHub tag. Push `v1.2.0` (or newer) and it serves
`com.github.SoftinoProducts:notifier-java-sdk:<tag>`, for example
`com.github.SoftinoProducts:notifier-java-sdk:v1.2.0`. Release tags are `v`-prefixed because
the Maven Central publish workflow keys on `v*`; the artifact version comes from `pom.xml`.

---

## License

Proprietary; owned by **SoftinoProducts**. Terms defined by the owner.
