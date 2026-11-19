package com.softino.notifier.internal;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A transport-agnostic request description for one async attempt. Building it (including the
 * JSON body) happens on an SDK dispatcher thread, never on the caller's thread.
 *
 * @param <T> the caller-facing result type parsed from a 2xx response
 */
public final class RequestSpec<T> {

    /**
     * Parses a 2xx JSON object into the caller-facing result.
     *
     * @param <T> the caller-facing result type
     */
    public interface Parser<T> {
        T parse(JsonObject body, String idempotencyKey) throws Exception;
    }

    /**
     * Builds the spec for one send, given the resolved idempotency key (may be null).
     *
     * @param <T> the caller-facing result type
     */
    public interface Factory<T> {
        RequestSpec<T> create(String idempotencyKey);
    }

    private final String method;
    private final String path;
    private final String body;
    private final boolean allowEmptyBody;
    private final Parser<T> parser;
    private final Map<String, String> headers;
    private final String idempotencyKey;
    private final String channelType;

    private RequestSpec(Builder<T> b) {
        this.method = b.method;
        this.path = b.path;
        this.body = b.body;
        this.allowEmptyBody = b.allowEmptyBody;
        this.parser = b.parser;
        this.headers = b.headers == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(b.headers));
        this.idempotencyKey = b.idempotencyKey;
        this.channelType = b.channelType;
    }

    public static <T> Builder<T> builder(String method, String path, Parser<T> parser) {
        return new Builder<T>(method, path, parser);
    }

    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getBody() { return body; }
    public boolean isAllowEmptyBody() { return allowEmptyBody; }
    public Parser<T> getParser() { return parser; }
    public Map<String, String> getHeaders() { return headers; }
    public String getIdempotencyKey() { return idempotencyKey; }
    /** Channel type for logs and listener events; null for a route or a status read. */
    public String getChannelType() { return channelType; }

    public static final class Builder<T> {
        private final String method;
        private final String path;
        private final Parser<T> parser;
        private String body;
        private boolean allowEmptyBody;
        private Map<String, String> headers;
        private String idempotencyKey;
        private String channelType;

        private Builder(String method, String path, Parser<T> parser) {
            this.method = method;
            this.path = path;
            this.parser = parser;
        }

        public Builder<T> body(String body) { this.body = body; return this; }

        public Builder<T> allowEmptyBody() { this.allowEmptyBody = true; return this; }

        /** An extra request header (e.g. a future hub feature); never contains a secret body. */
        public Builder<T> header(String name, String value) {
            if (name == null || value == null) return this;
            if (this.headers == null) this.headers = new LinkedHashMap<>();
            this.headers.put(name, value);
            return this;
        }

        public Builder<T> idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder<T> channelType(String channelType) {
            this.channelType = channelType;
            return this;
        }

        public RequestSpec<T> build() {
            return new RequestSpec<T>(this);
        }
    }
}
