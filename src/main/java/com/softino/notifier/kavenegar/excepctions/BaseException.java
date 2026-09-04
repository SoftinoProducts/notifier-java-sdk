package com.softino.notifier.kavenegar.excepctions;

/**
 * Base unchecked exception for the Kavenegar-compatible facade. Mirrors Kavenegar-java's
 * {@code BaseException}. Callers that catch this type (or the subclass {@link HttpException}
 * / {@link ApiException}) keep working unchanged after a package rename.
 */
public class BaseException extends RuntimeException {

    public BaseException(String message) {
        super(message);
    }
}
