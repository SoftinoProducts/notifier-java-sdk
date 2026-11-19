# Non-blocking demo

A standalone, runnable demonstration of the 1.2 non-blocking API. It starts a local stub hub
that answers after 1.5s, then:

1. calls the blocking `verifyLookup` and prints how long the caller is held;
2. calls `verifyLookupAndForget` and prints how long the caller is held;
3. enqueues 1,000 OTP sends and prints the p50/p99 caller return time, SDK thread count, and
   queue depth;
4. waits for drain and prints final stats.

No API key or external network is required. The SDK must be installed to the local Maven
repository first.

```bash
# from notifier-java-sdk/
mvn -q -DskipTests install

# from notifier-java-sdk/demo/
mvn -q compile exec:java

# or simply
./run.sh
```

Expected shape of the output (numbers vary):

```text
verifyLookup (blocking)        : caller held for 1521787 µs
verifyLookupAndForget          : caller returned in 116 µs
Burst of 1000 OTP enqueues
  p50 caller return            : 4 µs
  p99 caller return            : 121 µs
  SDK threads                  : 6
Drained=true stats=NotifierStats{accepted=1002, completed=1002, failed=0, ...}
Delivery itself still depends on the hub; only the caller wait is gone.
```

The demo deliberately uses a 1.5s hub delay only for the first calls; the 1,000-send burst uses
a 20ms delay so the example completes promptly.
