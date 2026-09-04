# Notifier Java SDK

[![Java](https://img.shields.io/badge/Java-8-orange)](https://adoptium.net/)
[![Maven Central](https://img.shields.io/maven-central/v/com.softino/notifier-java-sdk)](https://search.maven.org/artifact/com.softino/notifier-java-sdk)
[![License](https://img.shields.io/badge/License-Proprietary-blue)](LICENSE)

A channel-agnostic Java client for the **Notifier** multi-channel notification hub.

It is designed to be a **drop-in replacement for [`kavenegar-java`](https://github.com/kavenegar/kavenegar-java)**
— existing teams that used Kavenegar can switch to Notifier by changing **one import**, keeping the
same method calls and result types. It is intentionally **not** limited to SMS: the same surface
sends **email, web-push, Telegram, Slack, Bale and any future channel**.

```java
// Before (kavenegar-java)
import ir.kavenegar.api.KavenegarApi;
KavenegarApi api = new KavenegarApi("YOUR-KEY");
api.verifyLookup("+989120000000", "123456", "verify");

// After (notifier-java-sdk) — same calls, one import changed
import com.softino.notifier.kavenegar.KavenegarApi;
KavenegarApi api = new KavenegarApi("YOUR-KEY");
api.verifyLookup("+989120000000", "123456", "verify");
```

---

## Table of contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
  - [JitPack (recommended for private/public GitHub)](#jitpack-recommended-for-privatepublic-github)
  - [Maven Central](#maven-central)
- [Quick start](#quick-start)
- [Core API](#core-api)
  - [Sending an arbitrary body](#sending-an-arbitrary-body)
  - [Sending via a template (by id)](#sending-via-a-template-by-id)
  - [Sending via a template (by name)](#sending-via-a-template-by-name)
  - [Bulk send](#bulk-send)
  - [Status lookup](#status-lookup)
  - [History / listing](#history--listing)
- [Channels](#channels)
  - [Built-in channel types](#built-in-channel-types)
  - [Typed channel accessors](#typed-channel-accessors)
  - [Extending to new channels](#extending-to-new-channels)
- [Kavenegar compatibility](#kavenegar-compatibility)
- [Error handling](#error-handling)
- [Status model](#status-model)
- [Configuration](#configuration)
- [Building from source](#building-from-source)
- [Publishing](#publishing)
- [Project layout](#project-layout)

---

## Features

- **One SDK for every channel** — send SMS/email/web-push/Telegram/Slack/Bale (and more) with the
  same methods; a channel is just a `ChannelType` value.
- **Kavenegar drop-in** — a `com.softino.notifier.kavenegar.KavenegarApi` facade with identical
  signatures and result types, so migrating callers only change the import.
- **Templates, by id or by name** — backend-rendered content with variable substitution.
- **Bulk send** — many recipients in a single request (backend fan-out).
- **Status tracking** — by Notifier UUID **or** by provider message id (Kavenegar `messageid`,
  SMS.ir `messageId`, …).
- **History** — paginated, filterable listing with an opaque cursor.
- **Idempotency, scheduling, callbacks, metadata, locale** — carried through `SendOptions`.
- **Java 8** — compiled to target Java 8, so it runs on any JDK 8+ without module hacks. (The build
  uses `source`/`target` 1.8 so it also works when built with a real JDK 8 toolchain. Set a
  `--release 8` toolchain in your IDE to catch accidental Java 9+ API usage at compile time.)

## Requirements

- **Java 8+** (the artifact is a plain Java 8 jar).
- **Maven** 3.6+ (to build from source) — or just declare it as a dependency.
- A **Notifier API key** from your tenant, and optionally a custom base URL.

## Installation

### JitPack (recommended for private/public GitHub)

The release is published from a GitHub tag, so it installs through
[JitPack](https://jitpack.io). Add the repository and dependency:

**Maven**

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

**Gradle**

```groovy
repositories { maven { url 'https://jitpack.io' } }

dependencies {
    implementation 'com.github.SoftinoProducts:notifier-java-sdk:1.0.0'
}
```

> JitPack builds the artifact from the tag on demand. The repo must be reachable by JitPack
> (public, or via a JitPack token for a private repo). The dependency `version` matches the git tag.

### Maven Central

The project also ships a `release` profile that attaches sources + javadoc and GPG-signs for
OSSRH/Maven Central. Once published, the coordinates are:

```xml
<dependency>
    <groupId>com.softino</groupId>
    <artifactId>notifier-java-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick start

```java
import com.softino.notifier.*;

NotifierApi api = new NotifierApi("YOUR-API-KEY");

// 1. Send a plain SMS
SendResult r = api.send(ChannelType.SMS, "+989120000000", Content.of("Your OTP is 1234"));

// 2. Send an email with a subject
api.send(ChannelType.EMAIL, "customer@example.com", Content.of("Invoice", "Please see the attached invoice."));

// 3. Send a web-push
api.send(ChannelType.WEBPUSH, "device-token-or-user-id", Content.of("You have a new message"));

// 4. Print the notification id (to poll status later)
System.out.println(r.getId());
```

`NotifierApi` is `AutoCloseable`; call `close()` when the client is no longer needed (it shuts
down the underlying HTTP connection pool).

## Core API

### Sending an arbitrary body

```java
// Simple body
api.send(ChannelType.SMS, recipient, Content.of("Hello"));

// Body + subject (email) and options
SendOptions opts = SendOptions.builder()
        .channelId("optional-channel-uuid")   // pin to a specific channel
        .groupId("optional-lb-group-uuid")    // route via a channel group (load balancing)
        .idempotencyKey("order-123")          // deduplicate retries
        .sendAt("2026-01-01T09:00:00Z")       // schedule for later
        .callbackUrl("https://your-app/webhook") // receive status callbacks
        .locale("en")                          // template locale
        .metadata(Collections.singletonMap("orderId", "12345"))
        .build();

SendResult r = api.send(ChannelType.SMS, recipient, Content.of("Your order is confirmed"), opts);
```

### Sending via a template (by id)

```java
api.sendTemplate(
        ChannelType.SMS,
        "+989120000000",
        "otp-template",                                  // template UUID
        Collections.singletonMap("token", "123456"),     // vars substituted server-side
        SendOptions.builder().locale("en").build());
```

### Sending via a template (by name)

Use this when you only have a template **name** (e.g. a Kavenegar template name). The backend
resolves it against the tenant's templates for the channel + locale.

```java
api.sendTemplateByName(
        ChannelType.SMS,
        "+989120000000",
        "betaauth",
        Collections.singletonMap("token", "777"));
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

### Status lookup

```java
// By the Notifier UUID returned from send()
StatusResult byId = api.status(r.getId());

// By the provider message id (Kavenegar messageid / SMS.ir messageId)
StatusResult byProvider = api.statusByProviderMessageId("560152226");

if (byId.isDelivered()) { /* ... */ }
if (byId.isFailed())    { /* handle failure */ }
```

### History / listing

```java
HistoryPage page = api.listNotifications(
        HistoryQuery.builder()
                .limit(20)
                .status("delivered")
                .channelType(ChannelType.SMS)
                .from("2026-01-01T00:00:00Z")
                .to("2026-01-31T23:59:59Z")
                .build());

for (SendResult item : page.getItems()) {
    System.out.println(item.getId() + " -> " + item.getStatus());
}
if (page.hasNext()) {
    // continue with the opaque cursor
    api.listNotifications(HistoryQuery.builder().after(page.getNextCursor()).build());
}
```

## Channels

### Built-in channel types

`ChannelType` exposes constants for the supported channels. All are open/enumerable:

| Constant | Value | Notes |
|---|---|---|
| `ChannelType.SMS` | `sms` | Kavenegar, SMS.ir, … |
| `ChannelType.EMAIL` | `email` | SMTP providers |
| `ChannelType.WEBPUSH` | `webpush` | Centrifugo |
| `ChannelType.TELEGRAM` | `telegram` | |
| `ChannelType.SLACK` | `slack` | |
| `ChannelType.BALE` | `bale` | Safir / Bale messenger |

### Typed channel accessors

The generic `send()` covers every channel, but a few convenience accessors exist:

```java
api.email().send("to@example.com", "Subject", "Body");
api.telegram().send("chatId", "text");
api.slack().send("target", "text");
api.bale().send("chatId", "text");
```

### Extending to new channels

Because a channel is just a value, new channels work without an SDK change:

```java
ChannelType whatsapp = ChannelType.of("whatsapp");
api.send(whatsapp, "+989120000000", Content.of("Hi"));
```

## Kavenegar compatibility

For teams migrating from `kavenegar-java`, use the
`com.softino.notifier.kavenegar.KavenegarApi` facade. It mirrors the kavenegar-java surfaces
and returns the same model/enum/exception types (in the `kavenegar.*` namespace), so most callers
only change the import.

The facade is **type-compatible** with the calls used by the **trade-hub** project
(`KavenegarSMS` / `ISMS`): `verifyLookup`, `send`, `status(long)`, `statusLocalMessageId`,
`sendArray`, `countOutbox`, `countInbox`, `accountInfo`, `callMakeTTS`, and so on.

```java
import com.softino.notifier.kavenegar.KavenegarApi;
import com.softino.notifier.kavenegar.models.*;
import com.softino.notifier.kavenegar.enums.*;

KavenegarApi api = new KavenegarApi("YOUR-KEY");

SendResult r = api.verifyLookup("+989120000000", "123456", "verify");
Date  = r.getMessage();        // the rendered message
long  id   = r.getMessageId(); // provider message id
int   st   = r.getStatus();

StatusResult s = api.status(id);
MessageStatus status = s.getStatus();       // enum
String text = s.getStatusText();            // human statusText (mapped from Notifier status)
```

> Methods that map to Notifier features Notifier does not implement fail loudly with a
> `BaseException` (subclass) rather than silently no-op, so migrated code catches the familiar
> kavenegar exception types.

## Error handling

All failures surface as checked/unchecked exceptions from the `com.softino.notifier.exception`
(or the `kavenegar.excepctions` facade) hierarchies:

| Exception | Meaning |
|---|---|
| `NotifierException` | Base type for SDK errors. |
| `ApiException` | A business/API error (e.g. duplicate idempotency key, invalid request). Carries a `code`. |
| `HttpException` | Transport/HTTP failure (status, connect, timeout). Carries the HTTP `code`. |

```java
try {
    api.send(ChannelType.SMS, recipient, Content.of("hi"));
} catch (ApiException e) {
    // business error — check e.getCode()
} catch (HttpException e) {
    // network/HTTP error — check e.getCode(), e.getStatusCode()
}
```

## Status model

Notifier uses a coarse lifecycle. `SendResult`/`StatusResult` expose:

- `isQueued()`, `isDelivered()`, `isFailed()`
- `getProvider()`, `getProviderMessageId()`, `getSentAt()`, `getError()`

The Kavenegar facade maps these onto `kavenegar.enums.MessageStatus` + `StatusResult.getStatusText()`
so migrated code keeps working.

## Configuration

Construct the client:

```java
// Default base URL (https://notifier-api.vibe.ir)
NotifierApi api = new NotifierApi("YOUR-API-KEY");

// Custom base URL (self-hosted / staging)
NotifierApi api = new NotifierApi("YOUR-API-KEY", "https://notifier-api.internal");

// Timeouts (connect, socket, in ms) + max pooled connections per route
NotifierApi api = new NotifierApi("YOUR-API-KEY", "https://notifier-api.vibe.ir",
        5_000, 15_000, 50);
```

## Building from source

```bash
mvn clean package      # compiles (strict Java 8) and runs the test suite
mvn test               # unit + embedded-server contract tests
```

The test suite includes an end-to-end contract test that spins up an embedded HTTP server
emulating the Notifier API, so no network is required. A live integration test
(`LiveApiIT`) is named `*IT` and is excluded from `mvn test`; run it only with credentials:

```bash
NOTIFIER_API_KEY="..." NOTIFIER_BASE_URL="https://notifier-api.vibe.ir" \
  mvn -Dtest=LiveApiIT test
```

## Publishing

- **JitPack** — push a tag (e.g. `1.0.0`). JitPack builds and serves
  `com.github.SoftinoProducts:notifier-java-sdk:1.0.0`. This is the path used by trade-hub.
- **Maven Central (OSSRH)** — run `mvn -P release deploy`, which attaches sources + javadoc and
  GPG-signs. Requires OSSRH credentials + a signing key.

## Project layout

```
src/main/java/com/softino/notifier/
  NotifierApi.java              # core, channel-agnostic client
  ChannelType.java              # open enum-like channel type
  Content.java                  # body (+ subject)
  SendOptions.java              # options: template, idempotency, schedule, callback, locale…
  RecipientMessage.java         # bulk entry
  SendResult.java / StatusResult.java / BulkResult.java
  HistoryQuery.java / HistoryPage.java
  channel/                      # Email / Telegram / Slack / Bale convenience accessors
  exception/                    # NotifierException / ApiException / HttpException
  kavenegar/                    # Kavenegar-compatible facade + models/enums/exceptions
src/test/java/...               # SDK, contract, and trade-hub-compatibility tests
```

---

## License

This project is proprietary and owned by **SoftinoProducts**. No `LICENSE` file is bundled with
the repository; the terms of use are defined by the owner. Ping the maintainers for the correct
license file before redistributing.
