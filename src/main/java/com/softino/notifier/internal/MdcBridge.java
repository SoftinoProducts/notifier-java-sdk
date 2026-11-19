package com.softino.notifier.internal;

import java.lang.reflect.Method;

/**
 * Best-effort SLF4J MDC propagation without making SLF4J a required dependency.
 *
 * <p>When {@code org.slf4j.MDC} is on the classpath (Spring Boot, most services), the SDK puts
 * {@code traceId}, {@code idempotencyKey} and {@code notificationId} into the MDC while it
 * invokes a listener or writes a log record, then removes them. Without SLF4J every call is a
 * no-op, and the same fields are still present in the SDK's own log message.</p>
 */
final class MdcBridge {

    private static final Method PUT = method("put", String.class, String.class);
    private static final Method REMOVE = method("remove", String.class);

    private MdcBridge() {
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            return Class.forName("org.slf4j.MDC").getMethod(name, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void put(String key, String value) {
        if (PUT == null || key == null || value == null) return;
        try {
            PUT.invoke(null, key, value);
        } catch (Throwable ignored) {
            // MDC is optional; never let observability break a send.
        }
    }

    static void remove(String key) {
        if (REMOVE == null || key == null) return;
        try {
            REMOVE.invoke(null, key);
        } catch (Throwable ignored) {
            // ignored
        }
    }
}
