package com.softino.notifier.internal;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Structured, redacted logging for the SDK.
 *
 * <p>Only identifiers, status codes and outcomes are ever logged. Message bodies, template
 * tokens, OTPs and full recipient numbers are never passed to this class; exception messages
 * are reduced to a type and a single-line, length-capped text.</p>
 *
 * <p>Uses {@code java.util.logging} so the SDK keeps zero required logging dependencies. Set
 * the {@code com.softino.notifier} logger to {@code FINE} to see successful sends; failures log
 * at {@code WARNING}.</p>
 */
public final class NotifierLog {

    public static final Logger LOG = Logger.getLogger("com.softino.notifier");

    private NotifierLog() {
    }

    public static void fine(String format, Object... args) {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(format(format, args));
        }
    }

    public static void info(String format, Object... args) {
        if (LOG.isLoggable(Level.INFO)) {
            LOG.info(format(format, args));
        }
    }

    public static void warn(String format, Object... args) {
        if (LOG.isLoggable(Level.WARNING)) {
            LOG.warning(format(format, args));
        }
    }

    private static String format(String format, Object... args) {
        try {
            return String.format(Locale.ROOT, format, args);
        } catch (RuntimeException e) {
            return format;
        }
    }

    /** A single-line, capped description of a throwable that cannot carry a message body. */
    public static String safeMessage(Throwable t) {
        if (t == null) return "none";
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ');
        if (singleLine.length() > 200) {
            singleLine = singleLine.substring(0, 200) + "…";
        }
        return t.getClass().getSimpleName() + ": " + singleLine;
    }
}
