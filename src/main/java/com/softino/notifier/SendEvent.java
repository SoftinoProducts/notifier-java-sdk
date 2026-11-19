package com.softino.notifier;

/**
 * Immutable, log-safe context for one asynchronous send, handed to a {@link SendListener}.
 *
 * <p>It deliberately carries identifiers only: no message body, template token, OTP or full
 * recipient number. Correlate a callback with your own request by
 * {@link #getIdempotencyKey()} or {@link #getTraceId()}.</p>
 */
public final class SendEvent {

    private final SendPriority priority;
    private final String channelType;
    private final String idempotencyKey;
    private final String traceId;
    private final String notificationId;
    private final int attempt;

    /**
     * @param priority       the lane the send was accepted into
     * @param channelType    the channel type value, or {@code null} for a route/status request
     * @param idempotencyKey the key on the wire (consumer-supplied or SDK-generated), or {@code null}
     * @param traceId        the caller's trace id, or {@code null}
     * @param notificationId the Notifier UUID once the hub has accepted the send, else {@code null}
     * @param attempt        1 for the first attempt, 2 after the first retry, ...; 0 when an
     *                       accepted send was failed before it could be attempted (for example
     *                       during shutdown)
     */
    public SendEvent(SendPriority priority, String channelType, String idempotencyKey,
                     String traceId, String notificationId, int attempt) {
        this.priority = priority;
        this.channelType = channelType;
        this.idempotencyKey = idempotencyKey;
        this.traceId = traceId;
        this.notificationId = notificationId;
        this.attempt = attempt;
    }

    public SendPriority getPriority() { return priority; }
    public String getChannelType() { return channelType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getTraceId() { return traceId; }
    public String getNotificationId() { return notificationId; }
    public int getAttempt() { return attempt; }

    @Override
    public String toString() {
        return "SendEvent{priority=" + priority
                + ", channelType=" + channelType
                + ", idempotencyKey=" + idempotencyKey
                + ", traceId=" + traceId
                + ", notificationId=" + notificationId
                + ", attempt=" + attempt + '}';
    }
}
