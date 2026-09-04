# Notifier Java SDK

[![Java](https://img.shields.io/badge/Java-8-orange)](https://adoptium.net/)
[![Maven Central](https://img.shields.io/maven-central/v/com.softino/notifier-java-sdk)](https://search.maven.org/artifact/com.softino/notifier-java-sdk)
[![License](https://img.shields.io/badge/License-Proprietary-blue)](LICENSE)

A Java client for the **Notifier** multi-channel notification hub, built as a **drop-in replacement
for [`kavenegar-java`](https://github.com/kavenegar/kavenegar-java)**.

If you currently use the Kavenegar SDK, you can migrate by changing **one import** and keeping your
existing `verifyLookup`/`send`/`status` calls. Kavenegar **template sends** map directly to Notifier's
**send-with-template-by-name** — the headline feature of this SDK.

```java
// Before (kavenegar-java)
import ir.kavenegar.api.KavenegarApi;
KavenegarApi api = new KavenegarApi("YOUR-KEY");
api.verifyLookup("+989120000000", "123456", "betaauth");   // template by name

// After (notifier-java-sdk) — one import changed, template name kept
import com.softino.notifier.kavenegar.KavenegarApi;
KavenegarApi api = new KavenegarApi("YOUR-KEY");
api.verifyLookup("+989120000000", "123456", "betaauth");   // still works
```

Or, using the channel-agnostic core directly:

```java
import com.softino.notifier.*;
NotifierApi api = new NotifierApi("YOUR-API-KEY");
api.sendTemplateByName(ChannelType.SMS, "+989120000000", "betaauth",
        Collections.singletonMap("token", "123456"));       // same template name
```

> The **template name** is the contract between the two SDKs: a Kavenegar template name
> (e.g. `betaauth`, `salerequest`, `salesuccess`) is the exact name you create in Notifier and pass
> to `sendTemplateByName`.

---

## Table of contents

- [Why migrate from kavenegar-java](#why-migrate-from-kavenegar-java)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
  - [Maven Central](#maven-central)
  - [JitPack (private/public GitHub)](#jitpack-privatepublic-github)
- [Quick start (send with a template by name)](#quick-start-send-with-a-template-by-name)
- [Sending with a template (by name)](#sending-with-a-template-by-name)
  - [Template variables](#template-variables)
  - [Locale](#locale)
- [Migrating from the Kavenegar SDK](#migrating-from-the-kavenegar-sdk)
  - [The facade](#the-facade)
  - [Kavenegar method → Notifier method](#kavenegar-method--notifier-method)
  - [verifyLookup template names](#verifylookup-template-names)
  - [Trade-hub example](#trade-hub-example)
- [Other send options](#other-send-options)
  - [Sending an arbitrary body](#sending-an-arbitrary-body)
  - [Sending via a template (by id)](#sending-via-a-template-by-id)
  - [Bulk send](#bulk-send)
- [Status lookup](#status-lookup)
- [History / listing](#history--listing)
- [Channels](#channels)
  - [Built-in channel types](#built-in-channel-types)
  - [Sending via Bale](#sending-via-bale)
  - [Extending to new channels](#extending-to-new-channels)
- [Error handling](#error-handling)
- [Status model](#status-model)
- [Configuration](#configuration)
- [Building from source](#building-from-source)
- [Publishing](#publishing)
- [Project layout](#project-layout)

---

## Why migrate from kavenegar-java

- **One import change.** `com.softino.notifier.kavenegar.KavenegarApi` mirrors the kavenegar-java
  surface, so `verifyLookup`/`send`/`status` keep compiling.
- **Template-by-name is first-class.** The template name you already use with `verifyLookup` is
  passed straight through to Notifier.
- **Same types.** The facade returns `kavenegar.models.*`, `kavenegar.enums.*` and
  `kavenegar.excepctions.*`, so your `MessageStatus`/`SendResult`/`HttpException` handling is unchanged.
- **More channels.** The same client also sends email, web-push, Telegram, Slack, Bale and any future
  channel — not just SMS.

## Features

- **Send with template by name** — `sendTemplateByName(channel, recipient, name, vars)`, the primary
  way to send; the template name is resolved server-side against your tenant's templates.
- **Kavenegar drop-in** — a `com.softino.notifier.kavenegar.KavenegarApi` facade with identical
  signatures and result types.
- **Templates by id or name** — backend-rendered content with variable substitution.
- **Bulk send** — many recipients in one request (backend fan-out).
- **Status tracking** — by Notifier UUID **or** by provider message id (Kavenegar `messageid`,
  SMS.ir `messageId`, Bale id).
- **History** — paginated, filterable listing with an opaque cursor.
- **Idempotency, scheduling, callbacks, metadata, locale** — via `SendOptions`.
- **Java 8** — a plain Java 8 jar.

## Requirements

- **Java 8+**.
- **Maven** 3.6+ (to build from source) — or just declare it as a dependency.
- A **Notifier API key** from your tenant, and optionally a custom base URL.

## Installation

### Maven Central

```xml
<dependency>
    <groupId>com.softino</groupId>
    <artifactId>notifier-java-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

### JitPack (private/public GitHub)

The release is built from a GitHub tag by [JitPack](https://jitpack.io):

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
<dependency>
    <groupId>com.github.SoftinoProducts</groupId>
    <artifactId>notifier-java-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle:

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.SoftinoProducts:notifier-java-sdk:1.0.0' }
```

---

## Quick start (send with a template by name)

```java
import com.softino.notifier.*;
import java.util.HashMap;
import java.util.Map;

NotifierApi api = new NotifierApi("YOUR-API-KEY");

// Send an SMS using a template you created in Notifier, referenced by NAME.
Map<String, Object> vars = new HashMap<>();
vars.put("token", "123456");          // substituted into the template server-side

SendResult r = api.sendTemplateByName(ChannelType.SMS, "+989120000000", "betaauth", vars);

System.out.println("notification id = " + r.getId());     // Notifier UUID
System.out.println("status          = " + r.getStatus()); // e.g. "queued"
System.out.println("provider msg id = " + r.getProviderMessageId());

// Poll the delivery status
StatusResult s = api.status(r.getId());
if (s.isDelivered()) { /* delivered */ }
```

`NotifierApi` is `AutoCloseable`; call `close()` when you're done (releases the HTTP connection pool).

---

## Sending with a template (by name)

This is the recommended way to send. You reference a template by **name** (the same name you use in
Kavenegar); the Notifier backend resolves it against the tenant's templates for the channel + locale,
renders it, and dispatches.

```java
// Template by name + variables
api.sendTemplateByName(ChannelType.SMS, "+989120000000", "betaauth",
        Collections.singletonMap("token", "123456"));

// With options (locale, callback, metadata, idempotency, schedule)
api.sendTemplateByName(ChannelType.SMS, "+989120000000", "order_confirmed",
        Collections.singletonMap("orderId", "12345"),
        SendOptions.builder()
                .locale("fa")
                .callbackUrl("https://your-app/sms-callback")
                .metadata(Collections.singletonMap("source", "trade-hub"))
                .idempotencyKey("order-12345")
                .build());
```

### Template variables

- **Recommended: pass values positionally.** The template declares an ordered `param_names`; the SDK
  sends a `template_params` array and the backend binds `params[0] → param_names[0]`, etc. You never
  need to know the placeholder names in the template body:

```java
// Positional (Option B) — recommended
api.sendTemplateByName(ChannelType.SMS, phone, "betaauth", "123456");                 // one param
api.sendTemplateByName(ChannelType.SMS, phone, "salesuccess", "50000", "Tehran");     // two params
```

- **Or by name (map).** Vars are sent as a `Map<String, Object>` and substituted server-side by the
  template's placeholders. Use the **same placeholder names your template defines**:

```java
api.sendTemplateByName(ChannelType.SMS, phone, "betaauth",
        Collections.singletonMap("token", otp));
```

**Migrating from Kavenegar:** `verifyLookup(phone, token, name)` sends its token values **positionally**,
so a migrated template must declare its `param_names` to match (e.g. `["token"]`, or
`["token","token2","token3"]`). For a template that uses name-based params instead, use the map
form on the core client:

```java
api.sendTemplateByName(ChannelType.SMS, phone, "salesuccess", Map.of("amount", amt, "shop", shop));
```

### Locale

A template is resolved by `(channel, name, locale)`. The default locale is `fa`. Pass `.locale(...)`
in `SendOptions` to select a different one:

```java
api.sendTemplateByName(ChannelType.SMS, phone, "login_otp",
        Collections.singletonMap("token", otp),
        SendOptions.builder().locale("en").build());
```

---

## Migrating from the Kavenegar SDK

### The facade

Keep your code shape and use `com.softino.notifier.kavenegar.KavenegarApi`. It returns the
kavenegar `models.*`/`enums.*`/`excepctions.*` types, so you mostly change one import:

```java
import com.softino.notifier.kavenegar.KavenegarApi;
import com.softino.notifier.kavenegar.models.*;
import com.softino.notifier.kavenegar.enums.*;
import com.softino.notifier.kavenegar.excepctions.*;

KavenegarApi api = new KavenegarApi("YOUR-KEY");

// Template send (by name) — same as before
SendResult r = api.verifyLookup("+989120000000", "123456", "betaauth");
String message = r.getMessage();   // rendered message text
long   msgId   = r.getMessageId(); // provider message id (Long)
int    status  = r.getStatus();    // int status

// Status by the provider message id
StatusResult s = api.status(msgId);
MessageStatus ms = s.getStatus();      // enum (e.g. Delivered)
String text = s.getStatusText();       // mapped statusText

// Plain send (non-template)
api.send("100085902", "+989120000000", "hello");
```

### Kavenegar method → Notifier method

| Kavenegar (`kavenegar-java`) | Notifier facade (`notifier-java-sdk`) | Notes |
|---|---|---|
| `verifyLookup(receptor, token, template)` | `verifyLookup(receptor, token, template)` | **unchanged** — template by name |
| `send(sender, receptor, message)` | `send(sender, receptor, message)` | unchanged (plain body) |
| `status(messageId /*long*/)` | `status(messageId /*long*/)` | returns kavenegar `StatusResult` |
| `statusLocalMessageId(localId)` | `statusLocalMessageId(localId)` | returns kavenegar model |
| `sendArray(...)` | `sendArray(...)` | unchanged |
| `countOutbox(...)` / `countInbox(...)` | `countOutbox(...)` / `countInbox(...)` | returns kavenegar model |
| `accountInfo()` | `accountInfo()` | returns kavenegar model |
| `callMakeTTS(...)` | `callMakeTTS(...)` | unchanged |

> Methods that map to Notifier features Notifier doesn't implement still return the kavenegar type
> but throw a `kavenegar.excepctions.BaseException`, so your existing `catch (BaseException)`
> logic keeps working.

### verifyLookup template names

`verifyLookup(receptor, token, templateName)` passes `templateName` straight through as the Notifier
**template name**. You must create a template in Notifier with that exact name (and matching channel
type + locale). For example, the names used by the trade-hub project:

| Template name | Trade-hub method | Notifier call |
|---|---|---|
| `betaauth` | `sendKYCOTP` / `sendLoginOTP` | `sendTemplateByName(SMS, phone, "betaauth", vars)` |
| `salerequest` | `sendOTPSMS` | `sendTemplateByName(SMS, phone, "salerequest", vars)` |
| `salesuccess` | `sendSaleSuccess` | `sendTemplateByName(SMS, phone, "salesuccess", vars)` |
| `salereverse` | `sendRefundMsg` | `sendTemplateByName(SMS, phone, "salereverse", vars)` |

### Trade-hub example

Here is the `KavenegarSMS` pattern from trade-hub (`sendLoginOTP`), before and after:

```java
// BEFORE (kavenegar-java)
private final KavenegarApi api = new KavenegarApi(apiKey);
public void sendLoginOTP(String otp, String phone) {
    api.verifyLookup(phone, otp, templateLoginOtp);   // templateLoginOtp = "betaauth"
}

// AFTER (notifier-java-sdk) — keep verifyLookup, one import changed
private final com.softino.notifier.kavenegar.KavenegarApi api =
        new com.softino.notifier.kavenegar.KavenegarApi(apiKey);
public void sendLoginOTP(String otp, String phone) {
    api.verifyLookup(phone, otp, templateLoginOtp);   // unchanged
}
```

To use the channel-agnostic core instead of the facade:

```java
public void sendLoginOTP(String otp, String phone) {
    notifierApi.sendTemplateByName(ChannelType.SMS, phone, templateLoginOtp,
            Collections.singletonMap("token", otp));
}
```

---

## Other send options

### Sending an arbitrary body

```java
// Plain body (no template)
SendResult r = api.send(ChannelType.SMS, recipient, Content.of("Hello"));
api.send(ChannelType.EMAIL, "to@example.com", Content.of("Invoice", "See attached."));
api.send(ChannelType.WEBPUSH, "token", Content.of("New message"));
```

### Sending via a template (by id)

Prefer by name unless you hold the template UUID:

```java
api.sendTemplate(ChannelType.SMS, "+989120000000", "otp-template-uuid",
        Collections.singletonMap("token", "123456"));
```

### Bulk send

```java
List<RecipientMessage> messages = Arrays.asList(
        RecipientMessage.builder().recipient("+989120000000")
                .subjectAndBody("Hi", "Your code is 99").build(),
        RecipientMessage.builder().recipient("+989121111111")
                .templateVars(Collections.singletonMap("token", "88")).build());

BulkResult b = api.bulk(ChannelType.SMS, messages);
System.out.println("batch=" + b.getBatchId() + " count=" + b.getCount());
```

## Status lookup

```java
// By the Notifier UUID from send()
StatusResult byId = api.status(r.getId());
if (byId.isDelivered()) { /* delivered */ }
else if (byId.isFailed()) { /* handle failure */ }
else if (byId.isQueued()) { /* in flight — poll again */ }

// By the provider's message id (Kavenegar messageid / SMS.ir messageId / Bale id)
StatusResult byProvider = api.statusByProviderMessageId(r.getProviderMessageId());
```

## History / listing

```java
HistoryPage page = api.listNotifications(
        HistoryQuery.builder()
                .limit(20)
                .status("delivered")
                .channelType(ChannelType.SMS)
                .from("2026-01-01T00:00:00Z")
                .to("2026-01-31T23:59:59Z")
                .build());

for (SendResult item : page.getItems()) System.out.println(item.getId() + " -> " + item.getStatus());
if (page.hasNext()) api.listNotifications(HistoryQuery.builder().after(page.getNextCursor()).build());
```

## Channels

`ChannelType` exposes constants for the supported channels; all are open/enumerable:

| Constant | Value | Notes |
|---|---|---|
| `ChannelType.SMS` | `sms` | Kavenegar, SMS.ir, … |
| `ChannelType.EMAIL` | `email` | SMTP providers |
| `ChannelType.WEBPUSH` | `webpush` | Centrifugo |
| `ChannelType.TELEGRAM` | `telegram` | |
| `ChannelType.SLACK` | `slack` | |
| `ChannelType.BALE` | `bale` | Safir / Bale messenger |

### Sending via Bale

The recipient is a **Bale chat id**; use the typed accessor or the generic form:

```java
SendResult r = api.bale().send("1234567890", "سلام، کد شما 1234 است");

// By template name, channel-agnostic
api.sendTemplateByName(ChannelType.BALE, "1234567890", "order_confirmed",
        Collections.singletonMap("orderId", "12345"));

// Generic form
api.send(ChannelType.BALE, "1234567890", Content.of("Hello from Bale"));
```

### Extending to new channels

```java
ChannelType whatsapp = ChannelType.of("whatsapp");
api.send(whatsapp, "+989120000000", Content.of("Hi"));
```

## Error handling

Failures surface as exceptions from `com.softino.notifier.exception` (or `kavenegar.excepctions`
through the facade):

| Exception | Meaning |
|---|---|
| `NotifierException` | Base type for SDK errors. |
| `ApiException` | Business/API error (e.g. duplicate idempotency key). Carries a `code`. |
| `HttpException` | Transport/HTTP failure (status, connect, timeout). Carries the HTTP `code`. |

```java
try {
    api.sendTemplateByName(ChannelType.SMS, recipient, "betaauth", vars);
} catch (ApiException e) {        // business error — check e.getCode()
} catch (HttpException e) {       // network/HTTP error — check e.getCode()
}
```

## Status model

Notifier uses a coarse lifecycle. `SendResult`/`StatusResult` expose `isQueued()`, `isDelivered()`,
`isFailed()`, `getProvider()`, `getProviderMessageId()`, `getSentAt()`, `getError()`. The Kavenegar
facade maps these onto `kavenegar.enums.MessageStatus` + `StatusResult.getStatusText()` so migrated
code keeps working.

## Configuration

```java
// Default base URL (https://notifier-api.vibe.ir)
NotifierApi api = new NotifierApi("YOUR-API-KEY");

// Custom base URL
NotifierApi api = new NotifierApi("YOUR-API-KEY", "https://notifier-api.internal");

// Timeouts (connect, socket, ms) + max pooled connections per route
NotifierApi api = new NotifierApi("YOUR-API-KEY", "https://notifier-api.vibe.ir", 5_000, 15_000, 50);
```

## Building from source

```bash
mvn clean package      # compiles and runs the test suite
mvn test               # unit + embedded-server contract tests
```

A live integration test (`LiveApiIT`) is named `*IT` and excluded from `mvn test`; run it only with
credentials:

```bash
NOTIFIER_API_KEY="..." NOTIFIER_BASE_URL="https://notifier-api.vibe.ir" mvn -Dtest=LiveApiIT test
```

## Publishing

- **JitPack** — push a tag (e.g. `1.0.0`); JitPack builds and serves
  `com.github.SoftinoProducts:notifier-java-sdk:1.0.0`. This is the path used by trade-hub.
- **Maven Central (OSSRH)** — `mvn -P release deploy` (attaches sources + javadoc, GPG-signs).

## Project layout

```
src/main/java/com/softino/notifier/
  NotifierApi.java              # core, channel-agnostic client (sendTemplateByName, …)
  ChannelType.java              # open enum-like channel type
  Content.java                  # body (+ subject)
  SendOptions.java              # template/locale/callback/idempotency/metadata…
  RecipientMessage.java         # bulk entry
  SendResult.java / StatusResult.java / BulkResult.java
  HistoryQuery.java / HistoryPage.java
  channel/                      # Email / Telegram / Slack / Bale accessors
  exception/                    # NotifierException / ApiException / HttpException
  kavenegar/                    # Kavenegar facade (models/enums/exceptions)
src/test/java/...               # SDK, contract, and trade-hub-compatibility tests
```

---

## License

This project is proprietary and owned by **SoftinoProducts**. No `LICENSE` file is bundled with
the repository; the terms of use are defined by the owner. Ping the maintainers for the correct
license file before redistributing.
