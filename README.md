# Notifier Java SDK

[![Java](https://img.shields.io/badge/Java-8-orange)](https://adoptium.net/)

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
    <version>1.0.3</version>
</dependency>
```

Gradle:

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.SoftinoProducts:notifier-java-sdk:1.0.3' }
```

Requires **Java 8+** and a Notifier API key (optionally a custom base URL — see
[Configuration](#configuration)).

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

`NotifierApi` is `AutoCloseable` — call `close()` when done (releases the HTTP connection pool).

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

## Error handling

| Exception | Meaning |
|---|---|
| `NotifierException` | Base type for SDK errors. |
| `ApiException` | Business/API error (e.g. duplicate idempotency key). Carries a `code`. |
| `HttpException` | Transport/HTTP failure. Carries a `code`. |

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

All channels use the same `sendTemplateByName`/`send` calls. Use `ChannelType.of("whatsapp")` for a
channel you define yourself.

### Also available

- **Bulk send** — `api.bulk(ChannelType.SMS, List<RecipientMessage>)`; each `RecipientMessage` supports a
  template by name (including the `group:template` shorthand) or a plain body.
- **History** — `api.listNotifications(HistoryQuery)` returns a paged list with a `next_cursor`.
- **Arbitrary body** — `api.send(ChannelType.SMS, recipient, Content.of("Hello"))` (no template).

---

## Configuration

```java
// Default base URL: https://notifier-api.vibe.ir
NotifierApi api = new NotifierApi("YOUR-API-KEY");

// Custom base URL (e.g. staging)
NotifierApi api = new NotifierApi("YOUR-API-KEY", "https://notifier-api.internal");

// Timeouts (connect, socket, ms) + max pooled connections per route
NotifierApi api = new NotifierApi("YOUR-API-KEY", "https://notifier-api.vibe.ir", 5_000, 15_000, 50);
```

---

## Building & publishing

```bash
mvn test      # unit + embedded-server contract tests
mvn package   # builds the jar
```

`LiveApiIT` is a live integration test (named `*IT`, excluded from `mvn test`):

```bash
NOTIFIER_API_KEY="..." NOTIFIER_BASE_URL="https://notifier-api.vibe.ir" mvn -Dtest=LiveApiIT test
```

JitPack builds from a GitHub tag. Push `1.0.3` (or newer) and it serves
`com.github.SoftinoProducts:notifier-java-sdk:<tag>`.

---

## License

Proprietary; owned by **SoftinoProducts**. Terms defined by the owner.
