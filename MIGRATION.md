# Migrating to the non-blocking API (1.2+)

This guide is for existing 1.1.x consumers. The blocking API is untouched, so migration is
opt-in and can be done one call site at a time.

## The one-line change for the sale/OTP path

`trade-hub` currently calls `verifyLookup` inside `/sale/request` and `/register/request` and
waits for the hub's HTTP response. Replace that call with `verifyLookupAndForget`; no other
application code has to change.

```java
// Before — the request thread waits for the hub (up to the socket timeout on failure).
SendResult result = api.verifyLookup(phone, otp, "vibe:salerequest");
transactionSms.setNotificationId(result.getNotificationId());
transactionSmsRepo.save(transactionSms);
```

```java
// After — the request thread only enqueues; nothing network-bound happens here.
String notificationId = null; // no longer available synchronously; see below
api.verifyLookupAndForget(phone, otp, "vibe:salerequest",
        SendOptions.builder()
                .idempotencyKey("otp:" + saleUuid)
                .traceId(saleUuid)
                .priority(SendPriority.HIGH)
                .deadline(Duration.ofSeconds(5))
                .build());
```

### If you need the notification id

`verifyLookupAndForget` returns `void`; it has no result by design. Use `verifyLookupAsync` when
the id is part of your own transaction:

```java
CompletableFuture<SendResult> future =
        api.verifyLookupAsync(phone, otp, "vibe:salerequest",
                SendOptions.builder()
                        .idempotencyKey("otp:" + saleUuid)
                        .traceId(saleUuid)
                        .deadline(Duration.ofSeconds(5))
                        .build());

// Do not call future.join()/get() on a request-handling thread, and do not run a DB write
// inside thenAccept/thenApply: those run on the SDK reactor thread and would stall all sends,
// including OTP. Hand the id to your own executor:
future.thenAccept(sent ->
        applicationExecutor.execute(() ->
                transactionSmsRepo.saveNotificationId(saleUuid, sent.getNotificationId())));
```

The SDK-generated idempotency key is available on the future's result
(`SendResult.getIdempotencyKey()`), so a callback that cannot run immediately can still
correlate the send with the hub.

## Choosing the right method

| Need | Method |
|---|---|
| Fire an OTP and do not care about the result | `verifyLookupAndForget(...)` |
| Fire an OTP and record the notification id later | `verifyLookupAsync(...)` + `thenAccept(...)` |
| Keep a synchronous API for a background job | existing `verifyLookup(...)` |
| Know the hub accepted before continuing | `verifyLookupAsync(...).get(...)` — only on a thread you own |

`send*AndForget` never throws a network error. It throws only two immediate local conditions:

- `NotifierQueueFullException` — the HIGH/BULK queue is full, nothing was sent. Wait for
  `getRetryAfterMillis()` and decide whether to resubmit.
- `NotifierClosedException` — the client is closed.

Everything else is visible through `api.stats()`, logs and the optional `SendListener`.

## Configuration for a service like trade-hub

```java
NotifierConfig config = NotifierConfig.builder(apiKey)
        .baseUrl("https://notifier-api.vibe.ir")
        .defaultDeadlineMillis(10_000)        // total deadline, including retries
        .asyncConnectTimeoutMillis(2_000)
        .asyncReadTimeoutMillis(5_000)
        .highPriorityQueueCapacity(2_000)     // OTP lane
        .bulkQueueCapacity(10_000)
        .maxRetries(2)
        .closeDrainMillis(5_000)
        .registerShutdownHook(true)
        // Leave this off until the hub honours Idempotency-Key. With it off, ambiguous
        // failures (read timeout, reset, 5xx) are not retried and cannot duplicate an SMS.
        .hubSupportsIdempotencyKey(false)
        .build();

KavenegarApi api = new KavenegarApi(new NotifierApi(config));
```

The existing `new KavenegarApi(apiKey)` / `new KavenegarApi(apiKey, baseUrl)` constructors still
work and use the same async defaults; the `NotifierApi` constructor is only needed when you want
to tune queues/retries or share a configured client.

## Behaviour changes to be aware of

| Topic | 1.1.x | 1.2 async path |
|---|---|---|
| Caller blocking | Until hub response (up to socket timeout) | None; local validation + queue offer only |
| Failure surface | `ApiException` / `HttpException` thrown | Typed exception in the future; AndForget counts it and does not throw |
| Delivery timeout | Socket timeout (up to 30s) | Total deadline (default 10s), then `NotifierTimeoutException` |
| Retries | None in the SDK | Up to 2, only when safe; ambiguous failures require hub idempotency support |
| Ordering | Request thread order | Priority lanes; no cross-lane ordering guarantee |
| Shutdown | Connection pool close | Drain window, then fail remaining work; no lost accepted future |

Blocking methods still use the old socket-based timeout and still throw
`ApiException`/`HttpException` with the same codes. Blocking exception messages no longer echo
the raw response body or the server's free-text `error` field (both can contain rendered
content); branch on the exception type and code instead of message text.

## Duplicate-safety rules

A duplicated OTP is worse than a missing one. The SDK does not retry a read timeout,
connection reset after write, or 5xx by default, because the hub may already have accepted the
message. Turn on `hubSupportsIdempotencyKey(true)` only after the hub team confirms that
`POST /v1/notifications` de-duplicates on `Idempotency-Key`. After that, every retry carries the
same key, including SDK-generated keys.

## Rolling back

Keep using the existing blocking methods, or revert the SDK dependency to 1.1.0. The new API is
additive and does not change the wire format of blocking calls.

## What does not get faster

Delivery to the phone. The hub and the operator still decide when an SMS arrives. The gain is
that a slow or down hub no longer holds an application request thread; it does not shorten
delivery time and does not promise a zero-latency code.
