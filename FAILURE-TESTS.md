# Failure and lifecycle test report (1.2.0)

Run date: 2026-11-20. Environment: OpenJDK 21.0.12.1 (Homebrew) and the CI-style Java 8 build,
embedded loopback `HttpServer` (no external network).

```bash
mvn -o -B test
```

The suite is deterministic in its functional assertions and uses no paid provider or live hub;
the few wall-clock bounds are deliberately generous to survive a slow CI machine. Full suite: **128 tests**
across JDK 8, 11, 17 and 21 (all green). The tests that exercise a
5-second hub deliberately configure short deadlines so the suite stays fast.

## Failure matrix

| Scenario | Test | Expected result | Status |
|---|---|---|---|
| Hub down, async | `AsyncSendTest.hubDownFailsFastInsteadOfWaitingForSocketTimeout` | Caller returns in µs; a refused connection fails immediately with a retryable typed exception, not after a 30s socket timeout | Pass |
| Hub down, fire-and-forget | `AsyncFailureTest.fireAndForgetNeverThrowsNetworkFailures` (500 variant) | No throw; `failed`/`dropped` counters increase | Pass |
| Slow hub (5s) | `AsyncSendTest.deadlineProducesTypedTimeoutWithoutWaitingForReadTimeout` | `NotifierTimeoutException` at the 300ms deadline; caller return unchanged | Pass |
| Dribbling response body | `AsyncFailureTest.deadlineIsEnforcedWhileResponseBodyDribbles` | Watchdog fires at ~400ms even though every byte would reset the socket timeout | Pass |
| Queue full, HIGH lane (forget + async) | `AsyncSendTest.fullQueueThrowsTypedLocalExceptionAndNeverBlocks`, `highPriorityAsyncQueueFullCompletesFutureWithTypedError` | `NotifierQueueFullException` with `retryAfterMillis` on both paths; no caller blocking | Pass |
| BULK backlog vs OTP | `AsyncSendTest.highPriorityLaneIsNotStarvedByBulkBacklog` | HIGH future completes in <500ms while BULK is still in flight | Pass |
| HTTP 503, idempotency off (default) | `AsyncFailureTest.serverErrorIsNotRetriedByDefault` | Exactly 1 request; `NotifierUnavailableException` (retryable); no retry | Pass |
| HTTP 503, idempotency on | `AsyncFailureTest.serverErrorIsRetriedWithSameIdempotencyKeyWhenSupported` | 2 requests, same `Idempotency-Key` in header and body, result carries the key | Pass |
| HTTP 429 + `Retry-After` | `AsyncSendTest.rateLimitedResponseCarriesRetryAfterHeader` | `NotifierRateLimitedException.retryAfterMillis` equals the header value (2s); no retry with cap 0 | Pass |
| HTTP 429 fallback + retry | `AsyncSendTest.rateLimitedResponseUsesConfiguredFallbackAndRetries` | Absent/zero header uses `rateLimitedRetryAfterMillis`; listener sees that value; retry succeeds | Pass |
| Read timeout, idempotency off | `AsyncFailureTest.readTimeoutIsNotRetriedWithoutIdempotencySupport` | 1 request; `NotifierTimeoutException`; no duplicate | Pass |
| Read timeout, idempotency on | `AsyncFailureTest.readTimeoutIsRetriedWithSameKeyWhenIdempotencyIsSupported` | 2 requests, same key; future still times out | Pass |
| HTTP 400 | `AsyncFailureTest.invalidRequestIsNeverRetried` | `NotifierInvalidRequestException`; 1 request even with retries enabled | Pass |
| Deadline stops retries | `AsyncFailureTest.deadlineStopsRetriesEvenWhenIdempotencyIsSupported` | First attempt ends at the deadline; no further attempt; `NotifierTimeoutException` | Pass |
| Connection refused | `AsyncFailureTest.connectionRefusedIsRetriedEvenWithoutIdempotencySupport` | Safe pre-send retry; 2 retries counted | Pass |
| `close()` under load | `AsyncLifecycleTest.closeResolvesEveryAcceptedFutureAndLeavesNoThreads` | All 300 accepted futures settled; counters agree; no `notifier-*` thread remains; new sends throw `NotifierClosedException` | Pass |
| `close()` with a queued retry | `AsyncLifecycleTest.closeSettlesAQueuedRetry` | The retry timer is cancelled and its future completes with `NotifierClosedException` | Pass |
| Throwing listener | `AsyncSendTest.throwingListenerCannotBreakDispatch` | Send still completes; listener exceptions are contained | Pass |
| Healthy `close()` drain | `AsyncLifecycleTest.closeDrainsWhenTheHubIsHealthy` | 100/100 accepted sends complete; no lost future | Pass |
| 10,000 concurrent sends | `AsyncLifecycleTest.tenThousandConcurrentSendsStayBounded` | All 10,000 accepted, ≤10 SDK threads, queue bounded, all futures settle | Pass |
| Thread naming/daemon flag | `AsyncLifecycleTest.sdkThreadsAreDaemonAndNamed` | Names match `notifier-(sender-high\|sender-bulk\|reactor\|retry)-N`; all daemons | Pass |
| Log/exception redaction | `AsyncSendTest.logsAndExceptionMessagesNeverContainOtpOrBody` | No OTP, body or full recipient in captured SDK logs or exception messages | Pass |
| Blocking compatibility | `ContractTest`, `TradeHubCompatTest`, `SdkTest`, `PushDeviceTest`, `CallbackVerifierTest`, `TransportHygieneTest` | All existing behaviour compiles and passes unchanged | Pass |
| Optional shutdown hook | `CompatibilityTest.shutdownHookIsOptionalAndCloseIsIdempotent` | Hook registration and removal on close are exercised; close is idempotent | Pass |
| Source hygiene | `SourceHygieneTest` | No `Thread.sleep`, `synchronized`, `ForkJoinPool.commonPool`, unbounded pool, or `new Thread` in the async engine; main sources scanned after comment stripping | Pass |
| End-to-end trade-hub flow | `EndToEndTest.tradeHubFlowEndToEnd` | Blocking call unchanged; async and forget send the right headers/body; status read is not counted as a send; listener and stats agree | Pass |
| End-to-end retry + shutdown | `EndToEndTest.retryAndShutdownEndToEnd` | 503 retried once with the same key; close settles all 21 accepted sends; new work is refused; no SDK thread survives | Pass |
| Async facade parity | `TradeHubAsyncCompatTest.namedParamsAndSimulatedModesAreAvailableAsync`, `listAndArraySendsHaveAsyncEquivalents` | Named parameters, rehearsal, list and array sends all have async equivalents | Pass |

## Metrics and observation

| Test | Result |
|---|---|
| `MicrometerMetricsTest.publishesTimersCountersAndGauges` | Timer/counter tags include `outcome`/`priority`; gauges survive GC and do not become `NaN`; dropped send increments `notifier.send.dropped` |

## Notes on the failure design

- **No duplicate on ambiguous failure by default.** A read timeout, a reset after the request
  was written, and a 5xx can all mean the hub accepted the message. The SDK retries them only
  when `hubSupportsIdempotencyKey(true)` is set, and it reuses the same key on every attempt.
- **429 is retried without idempotency support.** A 429 is a rejection, not an ambiguous
  acceptance; the SDK honours `Retry-After` (seconds or HTTP date) and caps the wait at the
  deadline.
- **Queue-full is local and immediate.** The caller is never parked and no message is dropped
  silently; the exception points at the queue and suggests `retryAfterMillis`.
- **Shutdown never strands a future.** Accepted work is drained for `closeDrainMillis`, then the
  rest is failed typed and counted. Fire-and-forget sends that were accepted are visible in
  `dropped`; async futures are completed exceptionally rather than dropped.

## Reproducing a failure scenario

The suite starts its own hub; no environment variables or API key are needed. To watch the raw
strings, set the SDK logger to `FINE` (success) or leave it at `WARNING` (failures). Bodies and
OTPs are never included by design.

For the high-load case, run only the lifecycle suite:

```bash
mvn -o -B -Dtest=AsyncLifecycleTest test
```

For retry semantics:

```bash
mvn -o -B -Dtest=AsyncFailureTest test
```
