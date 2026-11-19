# Performance report: blocking vs non-blocking (1.2.0)

## Method

- Harness: `src/test/java/com/softino/notifier/AsyncBenchmark.java`, an embedded
  `com.sun.net.httpserver.HttpServer` on loopback with an artificial 2ms response delay (and a
  separate 5s-delay hub for the outage case). No external network.
- Each latency sample covers the **caller-visible call only**: `sendTemplateByNameAsync(...)`
  returns when the task is queued, and `sendTemplateByNameAndForget(...)` returns after the
  offer. Hub time is deliberately excluded from those numbers.
- p50/p99 are taken over the per-call samples after 500 warm-up sends.
- Throughput is reported as the wall time to submit and (for async) await the hub, so the
  submit wall time is not delivery throughput.
- Command:

```bash
mvn -o -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
    -Dexec.mainClass=com.softino.notifier.AsyncBenchmark -Dexec.classpathScope=test
```

## Environment

| | |
|---|---|
| Date | 2026-11-20 |
| Machine | Apple M-series, 10 CPUs, macOS 26.6.2 (aarch64) |
| JDK | OpenJDK 21.0.12.1 (Homebrew), `-Xmx` default |
| SDK | 1.2.0, `asyncConnectTimeoutMillis=2000`, `asyncReadTimeoutMillis=5000`, `maxRetries=0` |
| Hub | embedded `HttpServer`, loopback, 2ms artificial delay |

## Healthy hub (2ms per request)

| Scenario | Calls | Wall (ms) | p50 caller return | p99 caller return | SDK threads | Heap used* |
|---|---:|---:|---:|---:|---:|---:|
| `sendTemplateByName` (blocking), 1 thread | 500 | 1,780.3 | 3,200 µs | 9,886 µs | 0 | 26.8 MB |
| `sendTemplateByName` (blocking), 32 caller threads | 2,000 | 391.9 | n/a | n/a | 0 | 38.5 MB |
| `sendTemplateByNameAsync` submit, 10k | 10,000 | 12.4 | 0.7 µs | 11.0 µs | 6 | 183.1 MB |
| `sendTemplateByNameAndForget` submit, 10k | 10,000 | 9.2 | 0.5 µs | 6.1 µs | 6 | 353.9 MB |

\* Heap is the JVM used heap at that point in the harness, which retains the 10k futures for the
async phase. It is not a per-send allocation or a leak measurement; a steady-state service does
not retain every future.

Throughput observations:

- Blocking with 32 caller threads: `2,000 / 0.392s ≈ 5,102 sends/s`, each caller thread held for
  the full round trip.
- Async submission: `10,000 / 0.0124s ≈ 806,000 submissions/s` on this machine, limited by the
  harness loop, while delivery proceeds on 32 in-flight requests.
- Fire-and-forget submission: `10,000 / 0.0092s ≈ 1.09M submissions/s` on this machine. This is
  **enqueue** throughput, not SMS delivery throughput.

**Before vs after on the caller thread:** with a 2ms hub, a blocking call costs 3.2ms p50 /
9.9ms p99 of application-thread time. The non-blocking call costs 0.5µs p50 / 6.1µs p99 and
moves the round trip to SDK threads. Run-to-run p99 varies (roughly 2–11µs for async submit and
2–160µs for a 300-send burst against a 5s hub) but stays far under the 1ms/2ms acceptance bars.

## Slow hub (5s response delay)

| Scenario | Calls | Wall (ms) | p50 caller return | p99 caller return | SDK threads |
|---|---:|---:|---:|---:|---:|
| `sendTemplateByName` (blocking) | 1 | 5,008.3 | 5,000,000 µs | 5,000,000 µs | 0 |
| `sendTemplateByNameAndForget` submit | 300 | 7.0 | 2.6 µs | 157.3 µs | 6 |

The blocking call holds its thread for the entire 5s. The forget calls return in microseconds,
and `close()` (drain 200ms for this benchmark) settles the accepted work without waiting for the
5-second hub.

## Interpreting the numbers

- The async path has a small fixed thread inventory: 2 BULK dispatchers, 1 HIGH dispatcher, 2
  NIO reactor threads (one worker plus Apache's dispatch thread), 1 retry scheduler and 1
  deadline watchdog. Retry/deadline threads appear when first scheduled, so a snapshot during
  the submission burst often shows 5; the configured ceiling stays fixed. It does not create a
  thread per message.
- The measured p99 for `*AndForget` is well under the 1ms acceptance bar and for `*Async` under
  the 2ms bar. CI machines are slower and noisier; the tests assert generous bounds, while this
  report records a local run.
- A deadline is a hard caller-side bound. On an unreachable hub the future fails with
  `NotifierUnavailableException` or `NotifierTimeoutException` within the configured deadline
  (default 10s, OTP example 5s), never after a 30s socket timeout.
- `HIGH` has a reserved queue and in-flight quota, so bulk backlog cannot change the OTP path's
  caller-side numbers.
- The first async call lazily creates the engine and its threads; warm it up before measuring
  (the benchmark does 500 warm-up sends). Only blocking-only consumers avoid those threads
  entirely.

## Acceptance mapping

| Criterion | Evidence |
|---|---|
| `*AndForget` p99 < 1ms | 6.1µs p99 (this run); `AsyncSendTest` asserts <100ms for CI and a 1,000-send burst p99 <25ms |
| `*Async` p99 < 2ms; no caller I/O | 11.0µs p99; completion thread is `notifier-*`, caller returns before the 600ms hub response |
| Slow hub: same caller numbers | 157.3µs p99 under a 5s hub |
| Hub down: fast typed failure | `AsyncSendTest.hubDownFailsFastInsteadOfWaitingForSocketTimeout` |
| Total deadline while the body dribbles | `AsyncFailureTest.deadlineIsEnforcedWhileResponseBodyDribbles` |
| 10k concurrent sends, fixed threads, bounded queue | `AsyncLifecycleTest.tenThousandConcurrentSendsStayBounded` (≤10 SDK threads, queue ≤ capacity, typed rejects) |
| `close()` settles every accepted future | `AsyncLifecycleTest.closeResolvesEveryAcceptedFutureAndLeavesNoThreads` |
| No `Thread.sleep` / `synchronized` on the send path | `SourceHygieneTest` scans all main sources after comment stripping |
| No OTP/body in logs | `AsyncSendTest.logsAndExceptionMessagesNeverContainOtpOrBody` |
| Blocking compatibility | existing `ContractTest`, `TradeHubCompatTest`, `SdkTest`, Web Push tests still pass |
