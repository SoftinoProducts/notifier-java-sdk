package com.softino.notifier.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Shared Gson instance for the async path. Only parsed, never configured with default typing.
 */
final class JsonSupport {

    static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    private JsonSupport() {
    }
}
