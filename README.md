# Notifier Java SDK

[![Java](https://img.shields.io/badge/Java-8-orange)](https://adoptium.net/)

A Java client for the **Notifier** multi-channel notification hub, built as a **drop-in replacement for
[`kavenegar-java`](https://github.com/kavenegar/kavenegar-java)**. Kavenegar **template sends** map
directly to Notifier's **send-with-template-by-name** — the headline feature of this SDK.

```java
// Before (kavenegar-java)
import ir.kavenegar.api.KavenegarApi;

// After (notifier-java-sdk) — same calls, different package
import com.softino.notifier.kavenegar.KavenegarApi;

KavenegarApi api = new KavenegarApi("YOUR-KEY");
api.verifyLookup("+989120000000", "123456", "verify");   // template by name
```

> When migrating, replace the `com.kavenegar.sdk.*` import prefix with `com.softino.notifier.kavenegar.*`
> (the facade plus any `models`/`enums`/`excepctions` imports). Method names and signatures are unchanged.

---

## Table of contents

- [Why migrate from kavenegar-java](#why-migrate-from-kavenegar-java)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
  - [JitPack](#jitpack)
- [Quick start (send with a template by name)](#quick-start-send-with-a-template-by-name)
- [Sending with a template (by name)](#sending-with-a-template-by-name)
  - [Pass values positionally (recommended)](#pass-values-positionally-recommended)
  - [Pass values by name (map)](#pass-values-by-name-map)
  - [Locale](#locale)
- [Migrating from the Kavenegar SDK](#migrating-from-the-kavenegar-sdk)
  - [The facade](#the-facade)
  - [Method mapping](#method-mapping)
  - [Example template names](#example-template-names)
- [Other ways to send](#other-ways-to-send)
  - [Arbitrary body](#arbitrary-body)
  - [Template by id](#template-by-id)
  - [Bulk send](#bulk-send)
- [Status lookup](#status-lookup)
- [History / listing](#history--listing)
- [Channels](#channels)
  - [Built-in channel types](#built-in-channel-types)
  - [Sending via Bale](#sending-via-bale)
  - [Any new channel](#any-new-channel)
- [Error handling](#error-handling)
- [Status model](#status-model)
- [Configuration](#configuration)
- [Building from source](#building-from-source)
- [Publishing](#publishing)
- [Project layout](#project-layout)

---

## Why migrate from kavenegar-java

- **One package prefix to change.** The facade keeps `verifyLookup`/`send`/`status` signatures and
  types, so your SMS code compiles after swapping the import.
- **Template-by-name is first-class.** The template name you already pass to `verifyLookup` is used
  directly by Notifier.
- **Same result types.** The facade returns `kavenegar.models.*`, `kavenegar.enums.*` and
  `kavenegar.excepctions.*`, so `MessageStatus`/`SendResult`/`HttpException` handling is unchanged.
- **More channels.** The same client sends email, web-push, Telegram, Slack, Bale and any future
  channel — not just SMS.

## Features

- **Send with template by name** — `sendTemplateByName(channel, recipient, name, …)`, the primary way to send.
- **Kavenegar drop-in** — a `com.softino.notifier.kavenegar.KavenegarApi` facade with identical signatures and types.
- **Positional template params** — pass values in order; they bind to the template's declared `param_names`.
- **Templates by id or name**, **bulk send**, **status tracking** (by Notifier UUID or provider message id), **paged history**.
- **Idempotency, scheduling, callbacks, metadata, locale** via `SendOptions`.
- **Java 8** — a plain Java 8 jar.

## Requirements

- **Java 8+**.
- **Maven** 3.6+ to build from source — or just declare the dependency.
- A **Notifier API key** (and optionally a custom base URL).

## Installation

### JitPack

Built from a GitHub tag:

```xml
<repositories>
    <repository><id>jitpack.io</id><url>https://jitpack.io</url></repository>
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
import java.util.Collections;

NotifierApi api = new NotifierApi("YOUR-API-KEY");

// Send by template name; values bound positionally to the template's declared param_names.
SendResult r = api.sendTemplateByName(ChannelType.SMS, "+989120000000", "verify", "123456");

System.out.println("notification id = " + r.getId());      // Notifier UUID
System.out.println("status          = " + r.getStatus());  // e.g. "queued"
System.out.println("provider msg id = " + r.getProviderMessageId());

// Poll delivery status
StatusResult s = api.status(r.getId());
if (s.isDelivered()) { /* delivered */ }
```

`NotifierApi` is `AutoCloseable`; call `close()` when done (releases the HTTP connection pool).

---

## Sending with a template (by name)

Reference a template by its **name** (the same name you used in Kavenegar). The backend resolves it
against the tenant's templates for the channel + locale, renders it, and dispatches.

> **Prerequisite:** the template must declare its parameter names in the platform (UI/API
> `param_names`) in the order you'll pass values. If it doesn't, use the *map* form below instead.

### Pass values positionally (recommended)

```java
// Values bind to param_names in order — no need to know placeholder names.
api.sendTemplateByName(ChannelType.SMS, phone, "verify", "123456");                // 1 param
api.sendTemplateByName(ChannelType.SMS, phone, "order_notice", "50000", "Tehran");    // 2 params

// With options (SendOptions comes before the positional params)
api.sendTemplateByName(ChannelType.SMS, phone, "order_confirmed",
        SendOptions.builder().locale("fa").callbackUrl("https://your-app/cb")
                .metadata(Collections.singletonMap("source", "web"))
                .idempotencyKey("order-12345").build(),
        "12345");
```

### Pass values by name (map)

Use this when you know (or prefer) the placeholder names:

```java
api.sendTemplateByName(ChannelType.SMS, phone, "verify", Collections.singletonMap("token", otp));
```

### Locale

A template is resolved by `(channel, name, locale)`. The default locale is `fa`; pass `.locale(...)`
to override:

```java
api.sendTemplateByName(ChannelType.SMS, phone, "login_otp",
        SendOptions.builder().locale("en").build(),
        "123456");
```

---

## Migrating from the Kavenegar SDK

### The facade

Keep your code and use `com.softino.notifier.kavenegar.KavenegarApi`. It returns the kavenegar
`models.*`/`enums.*`/`excepctions.*` types.

```java
import com.softino.notifier.kavenegar.KavenegarApi;
import com.softino.notifier.kavenegar.models.*;
import com.softino.notifier.kavenegar.enums.*;

KavenegarApi api = new KavenegarApi("YOUR-KEY");

// Template send (by name) — same as before
SendResult r = api.verifyLookup("+989120000000", "123456", "verify");
String message = r.getMessage();    // rendered text
long   msgId   = r.getMessageId();  // provider message id (Long)
int    status  = r.getStatus();

StatusResult s = api.status(msgId);
MessageStatus ms = s.getStatus();   // enum (e.g. Delivered)
String text = s.getStatusText();    // mapped statusText

api.send("100085902", "+989120000000", "hello");   // plain (non-template)
```

> **Important — template params are positional.** `verifyLookup(receptor, token, token2, token3,
> template)` sends its values as an ordered `template_params` array. For it to render, the Notifier
> template must declare its `param_names` in the same order — e.g. `["token"]`, or
> `["token","token2","token3"]`. If a template uses different placeholder names, use the map form
> `sendTemplateByName(channel, phone, name, vars)` instead of `verifyLookup`.

> **Route by group name too.** Use `"groupName:templateName"` to send through a channel group
> referenced by name: `api.verifyLookup(phone, "123456", "sms-group:verify")`. The part before the
> `:` is the group name; the rest is the template name. Equivalently, on the core client:
> `api.sendTemplateByName(ChannelType.SMS, phone, "verify", SendOptions.builder().groupName("sms-group").build(), "123456")`.

### Method mapping

| Kavenegar (`com.kavenegar.sdk.*`) | Notifier facade (`com.softino.notifier.kavenegar`) |
|---|---|
| `verifyLookup(receptor, token, template)` | `verifyLookup(receptor, token, template)` — unchanged |
| `send(sender, receptor, message)` | `send(sender, receptor, message)` — unchanged |
| `status(messageId /*long*/)` | `status(messageId /*long*/)` — returns kavenegar `StatusResult` |
| `statusLocalMessageId(localId)` | `statusLocalMessageId(localId)` |
| `sendArray(...)` | `sendArray(...)` |
| `countOutbox(...)` / `countInbox(...)` | `countOutbox(...)` / `countInbox(...)` |
| `accountInfo()` | `accountInfo()` |
| `callMakeTTS(...)` | `callMakeTTS(...)` |

Methods that map to features Notifier doesn't implement still return the kavenegar type but throw a
`kavenegar.excepctions.BaseException`, so existing `catch (BaseException)` logic keeps working.

### Example template names

A template name is just a string — the important thing is that your Notifier template declares the
`param_names` in the order you pass values. Common patterns:

| Template name | Params (in order) | Notifier call |
|---|---|---|
| `verify` | `token` | `sendTemplateByName(SMS, phone, "verify", "123456")` |
| `order_notice` | `amount`, `region` | `sendTemplateByName(SMS, phone, "order_notice", "50000", "Tehran")` |

A generic `verifyLookup` before and after — only the import changes:

```java
// BEFORE (com.kavenegar.sdk)
KavenegarApi api = new KavenegarApi(apiKey);
public void sendVerificationCode(String code, String phone) {
    api.verifyLookup(phone, code, templateName);   // templateName = "verify"
}

// AFTER (notifier-java-sdk) — keep verifyLookup, swap the import prefix
com.softino.notifier.kavenegar.KavenegarApi api =
        new com.softino.notifier.kavenegar.KavenegarApi(apiKey);
public void sendVerificationCode(String code, String phone) {
    api.verifyLookup(phone, code, templateName);   // unchanged
}
```

---

## Other ways to send

### Arbitrary body

```java
api.send(ChannelType.SMS, recipient, Content.of("Hello"));
api.send(ChannelType.EMAIL, "to@example.com", Content.of("Invoice", "See attached."));
api.send(ChannelType.WEBPUSH, "token", Content.of("New message"));
```

### Template by id

Prefer by name unless you hold the template UUID:

```java
api.sendTemplate(ChannelType.SMS, "+989120000000", "otp-template-uuid", "123456");
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
StatusResult byId = api.status(r.getId());                       // by Notifier UUID
if (byId.isDelivered()) { /* delivered */ }
else if (byId.isFailed()) { /* handle failure */ }
else if (byId.isQueued()) { /* in flight — poll again */ }

StatusResult byProvider = api.statusByProviderMessageId(r.getProviderMessageId()); // by provider id
```

## History / listing

```java
HistoryPage page = api.listNotifications(
        HistoryQuery.builder().limit(20).status("delivered").channelType(ChannelType.SMS)
                .from("2026-01-01T00:00:00Z").to("2026-01-31T23:59:59Z").build());
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

The recipient is a **Bale chat id**:

```java
SendResult r = api.bale().send("1234567890", "سلام، کد شما 1234 است");
api.sendTemplateByName(ChannelType.BALE, "1234567890", "order_confirmed", "12345"); // by template name
```

### Any new channel

```java
ChannelType whatsapp = ChannelType.of("whatsapp");
api.send(whatsapp, "+989120000000", Content.of("Hi"));
```

## Error handling

Exceptions come from `com.softino.notifier.exception` (or `kavenegar.excepctions` through the facade):

| Exception | Meaning |
|---|---|
| `NotifierException` | Base type for SDK errors. |
| `ApiException` | Business/API error (e.g. duplicate idempotency key). Carries a `code`. |
| `HttpException` | Transport/HTTP failure (status, connect, timeout). Carries a `code`. |

```java
try {
    api.sendTemplateByName(ChannelType.SMS, recipient, "verify", "123456");
} catch (ApiException e) {   // business error — e.getCode()
} catch (HttpException e) {  // network/HTTP error — e.getCode()
}
```

## Status model

`SendResult`/`StatusResult` expose `isQueued()`, `isDelivered()`, `isFailed()`, `getProvider()`,
`getProviderMessageId()`, `getSentAt()`, `getError()`. The Kavenegar facade maps these onto
`kavenegar.enums.MessageStatus` + `StatusResult.getStatusText()` so migrated code keeps working.

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

`LiveApiIT` is a live integration test (named `*IT`, excluded from `mvn test`). Run it only with
credentials:

```bash
NOTIFIER_API_KEY="..." NOTIFIER_BASE_URL="https://notifier-api.vibe.ir" mvn -Dtest=LiveApiIT test
```

## Publishing

- **JitPack** — push a tag (e.g. `1.0.0`); JitPack builds and serves
  `com.github.SoftinoProducts:notifier-java-sdk:1.0.0`.

## Project layout

```
src/main/java/com/softino/notifier/
  NotifierApi.java              # core client (sendTemplateByName, send, status, …)
  ChannelType.java              # open enum-like channel type
  Content.java                  # body (+ subject)
  SendOptions.java              # template/locale/callback/idempotency/metadata…
  RecipientMessage.java         # bulk entry
  SendResult.java / StatusResult.java / BulkResult.java
  HistoryQuery.java / HistoryPage.java
  channel/                      # Email / Telegram / Slack / Bale accessors
  exception/                    # NotifierException / ApiException / HttpException
  kavenegar/                    # Kavenegar facade (models/enums/exceptions)
src/test/java/...               # SDK, contract, and Kavenegar-compatibility tests
```

---

## License

This project is proprietary and owned by **SoftinoProducts**. No `LICENSE` file is bundled with the
repository; the terms of use are defined by the owner.
