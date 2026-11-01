package com.softino.notifier.exception;

/**
 * Thrown when a delivery callback cannot be authenticated.
 *
 * <p>{@link #getReason()} says why, so a receiver can log it and answer the right status
 * code: a stale timestamp and a wrong signature are both a {@code 401}, but they mean very
 * different things during an integration.</p>
 */
public class CallbackSignatureException extends NotifierException {

    /** Why a callback was refused. */
    public enum Reason {
        /** The request carried no body at all. */
        MISSING_BODY("missing request body"),
        /** The {@code X-Notifier-Timestamp} header is absent or is not Unix seconds. */
        MISSING_TIMESTAMP("missing callback timestamp"),
        /** The signed timestamp is outside the tolerance window, so the request is a replay. */
        STALE_TIMESTAMP("stale callback timestamp"),
        /** The digest is absent, malformed, or does not match the request. */
        INVALID_SIGNATURE("invalid callback signature");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        /** A short, log-safe description of the reason. */
        public String label() {
            return label;
        }
    }

    private final Reason reason;

    public CallbackSignatureException(Reason reason) {
        this(reason, reason.label());
    }

    public CallbackSignatureException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
