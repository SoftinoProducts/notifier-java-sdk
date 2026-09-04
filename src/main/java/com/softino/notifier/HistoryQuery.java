package com.softino.notifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Query parameters for {@link NotifierApi#listNotifications(HistoryQuery)}. All fields are
 * optional; it maps to the {@code GET /v1/notifications} filter parameters.
 */
public final class HistoryQuery {

    private final int limit;
    private final String after;          // opaque cursor from the previous page
    private final String status;         // queued | processing | delivered | failed
    private final ChannelType channelType;
    private final String from;           // RFC3339 start
    private final String to;             // RFC3339 end
    private final Map<String, String> extra;

    private HistoryQuery(Builder b) {
        this.limit = b.limit;
        this.after = b.after;
        this.status = b.status;
        this.channelType = b.channelType;
        this.from = b.from;
        this.to = b.to;
        this.extra = b.extra == null ? Collections.emptyMap() : new LinkedHashMap<>(b.extra);
    }

    public static Builder builder() { return new Builder(); }
    public static HistoryQuery none() { return new Builder().build(); }

    public int getLimit() { return limit; }
    public String getAfter() { return after; }
    public String getStatus() { return status; }
    public ChannelType getChannelType() { return channelType; }
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public Map<String, String> getExtra() { return extra; }

    public static final class Builder {
        private int limit;
        private String after;
        private String status;
        private ChannelType channelType;
        private String from;
        private String to;
        private Map<String, String> extra;

        public Builder limit(int limit) { this.limit = limit; return this; }
        public Builder after(String after) { this.after = after; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder channelType(ChannelType channelType) { this.channelType = channelType; return this; }
        public Builder from(String from) { this.from = from; return this; }
        public Builder to(String to) { this.to = to; return this; }
        public Builder extra(String name, String value) {
            if (this.extra == null) this.extra = new LinkedHashMap<>();
            this.extra.put(name, value);
            return this;
        }

        public HistoryQuery build() { return new HistoryQuery(this); }
    }
}
