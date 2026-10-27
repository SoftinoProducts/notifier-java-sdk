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
    <version>1.0.7</version>
</dependency>
```

Gradle:

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.SoftinoProducts:notifier-java-sdk:1.0.7' }
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
the phone resolves the Telegram leg automatically. List bindings with
`GET /v1/channels/{channel_id}/bindings`; remove one by its binding id with
`DELETE /v1/channels/{channel_id}/bindings/{binding_id}`. The token is single-use and
expires after 15 minutes.

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

JitPack builds from a GitHub tag. Push `1.0.7` (or newer) and it serves
`com.github.SoftinoProducts:notifier-java-sdk:<tag>`.

---

## License

Proprietary; owned by **SoftinoProducts**. Terms defined by the owner.
