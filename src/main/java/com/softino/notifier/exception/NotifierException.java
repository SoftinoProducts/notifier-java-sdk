package com.softino.notifier.exception;

/**
 * Base unchecked exception for the Notifier SDK. All SDK errors extend this so callers
 * can catch a single type. Mirrors Kavenegar's {@code BaseException} which also extends
 * {@code RuntimeException}.
 */
public class NotifierException extends RuntimeException {

    public NotifierException(String message) {
        super(message);
    }

    public NotifierException(String message, Throwable cause) {
        super(message, cause);
    }
}
