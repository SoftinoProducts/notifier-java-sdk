# Changelog

All notable changes to the Notifier Java SDK are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## 1.2.0 — 2026-11-20

Non-blocking send path. **No breaking source change**: every existing blocking method keeps its
signature and behaviour, and the minimum Java version remains **Java 8**.

### Added

- `send*Async` and `send*AndForget` on `NotifierApi` for every single-send shape: plain body,
  template by id/name (map and positional values), bulk, status, and Web Push device sends.
- `KavenegarApi.verifyLookupAsync` and `verifyLookupAndForget`, including named-parameter and
  simulated variants, plus single/list/array `sendAsync`/`sendAndForget`,
  `statusByNotificationIdAsync`, `stats()` and `close()`.
- `NotifierConfig` with independent blocking and async settings, bounded per-lane queues,
  dispatcher/reactor/retry thread counts, retry policy, close drain and listener registration.
- `SendPriority` (`HIGH`, `BULK`) with independent queues and in-flight quotas. The
  `verifyLookup*` family defaults to `HIGH`; generic sends default to `BULK`.
- Total send deadline (`SendOptions.deadline`, default 10s) covering connect, write, read,
  queueing and retries, enforced by an independent watchdog even when a response body keeps
  dribbling; typed `NotifierTimeoutException` instead of waiting to a socket timeout.
- `rateLimitedRetryAfterMillis` fallback for a 429 without a usable `Retry-After` header.
- Typed exception hierarchy with `isRetryable()`: `NotifierUnavailableException`,
  `NotifierTimeoutException`, `NotifierRateLimitedException`,
  `NotifierInvalidRequestException`, `NotifierQueueFullException`, `NotifierClosedException`.
- Bounded backpressure: queue-full never blocks and never silently drops; it throws
  `NotifierQueueFullException` (AndForget) or completes the future exceptionally (Async), with
  `retryAfterMillis`.
- Retry scheduler with exponential backoff and full jitter, no `Thread.sleep`. Ambiguous
  failures (read timeout, reset after send, 5xx, 408) are retried only when
  `NotifierConfig.hubSupportsIdempotencyKey(true)` is set; 429 and pre-send failures are safe.
- Idempotency keys: consumer-supplied or SDK-generated, sent as `Idempotency-Key` (and in the
  request body), reused on every retry and returned from `SendResult.getIdempotencyKey()`.
- `X-Priority` and `X-Trace-Id` headers; `traceId` is also copied into the SLF4J MDC when SLF4J
  is on the classpath. All SDK threads (including `notifier-deadline-*`) are daemon and named.
- `SendListener` observation SPI and optional `MicrometerSendListener` with
  `notifier.send.duration`, `notifier.queue.depth`, `notifier.send.in_flight`,
  `notifier.send.retries`, `notifier.send.rejected`, `notifier.send.dropped`.
- `NotifierApi.stats()`, `NotifierApi.awaitIdle(Duration)` and optional JVM shutdown hook.
- `demo/` example that shows a 1.5s hub round trip versus a sub-millisecond enqueue.
- Tests for caller-thread latency, 10k concurrent sends, queue backpressure, priority
  starvation, deadlines, retry safety, duplicate prevention, drain/shutdown, log redaction and
  100% backward compatibility.

### Changed

- The async transport is Apache HttpAsyncClient 4.1.5 (NIO reactor) instead of a blocking
  client wrapped in a thread pool. No `ForkJoinPool.commonPool`, no thread-per-message.
- `HttpException.isRetryable()` now reports `true` for HTTP 408/429/5xx on the blocking path.
- Blocking-path exception messages no longer embed the raw response body or the server's
  free-text `error` field (both can contain rendered content). `ApiException`/`HttpException`
  codes and types are unchanged; callers that matched on message text must switch to the code.
- Legacy constructors keep Apache's `0 = infinite/unlimited` convention for blocking connect,
  read and connection-request timeouts; only a non-positive pool size is rejected (as before).
- The NIO reactor wake-up interval is configurable (`ioSelectIntervalMillis`, default 100ms)
  because HttpAsyncClient 4.x enforces socket timeouts on that tick.

### Fixed

- A caller can no longer be held for 10–30 seconds on a hub outage when using the async API;
  an independent deadline watchdog now enforces the total deadline even when a hub sends
  headers and then dribbles the body (a socket timeout alone resets on every byte).
- Micrometer gauges no longer turn into `NaN` after a GC: the listener keeps a strong reference
  to the gauge state supplier, as Micrometer's weak-reference contract requires.
- A throwing `SendListener` (or a retry scheduler rejected during shutdown) can no longer kill
  a callback path or leave an accepted future unsettled.
- Shutdown no longer risks losing accepted work silently: every accepted future is completed
  or failed, and queued work is counted.

### Dependencies

- Added `org.apache.httpcomponents:httpasyncclient:4.1.5` (and pinned `httpcore-nio:4.4.16`)
  as a runtime dependency.
- Added `io.micrometer:micrometer-core:1.13.0` as an **optional** dependency; only
  `MicrometerSendListener` needs it.

### Hub coordination required for the full feature set (P1)

The following are sent but have no effect until the hub ships support:

1. `Idempotency-Key` de-duplication on `POST /v1/notifications`.
2. `X-Priority: high|bulk` and a separate OTP lane inside the hub queue.
3. A batch endpoint for `BULK`; `HIGH` messages are never batched by the SDK.

P0 (non-blocking caller path, bounded queues, deadlines, retries that are safe without
idempotency support, and shutdown) is independent of the hub and safe to deploy now.

### Explicit non-goals

- **Delivery time to the handset is not improved.** Notifier, the provider and the operator
  still decide when an SMS arrives.
- No "zero-latency code" promise. What becomes effectively zero is the caller's wait and thread
  occupancy, not the delivery latency.
- No exactly-once guarantee end to end. `AndForget` is at-most-once; `Async` plus a hub-honoured
  idempotency key is at-least-once with duplicate resistance in the hub.

### Compatibility

- Blocking `send*`, `status*`, `bulk`, Web Push and Kavenegar facade methods are unchanged and
  covered by the existing contract tests.
- `NotifierException` gained `isRetryable()` with a default of `false`; subclasses override it.
  This is additive for source and binary compatibility.
- New constructors and methods are additive. `KavenegarApi` now implements `AutoCloseable`.
- Minimum Java is still **8**; the CI matrix (8, 11, 17) is unchanged. Java 21 is tested locally.
