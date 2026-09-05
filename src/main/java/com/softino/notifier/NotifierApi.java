package com.softino.notifier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.softino.notifier.channel.BaleChannelApi;
import com.softino.notifier.channel.EmailChannelApi;
import com.softino.notifier.channel.SlackChannelApi;
import com.softino.notifier.channel.TelegramChannelApi;
import com.softino.notifier.exception.ApiException;
import com.softino.notifier.exception.HttpException;
import com.softino.notifier.exception.NotifierException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Channel-agnostic client for the Notifier notification hub.
 *
 * <p>Works for any channel the backend supports (SMS via any provider, email, webpush,
 * Telegram, Slack, Bale, ...) — the SDK never chooses the provider; the Notifier backend
 * resolves the provider from the tenant's channel configuration. Use {@link ChannelType}
 * constants or {@link ChannelType#of(String)} for brand-new channels.</p>
 *
 * <pre>{@code
 * NotifierApi api = new NotifierApi("your-X-API-Key");
 * SendResult r = api.send(ChannelType.SMS, "+989120000000", Content.of("Your OTP is 1234"));
 * StatusResult s = api.status(r.getId());
 * }</pre>
 *
 * <p>For a Kavenegar-java drop-in facade, see {@link KavenegarApi}.</p>
 */
public class NotifierApi implements AutoCloseable {

    public static final String DEFAULT_BASE_URL = "https://notifier-api.vibe.ir";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_CONN_REQUEST_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_MAX_CONNECTIONS = 20;

    private final String apiKey;
    private final String baseUrl;
    private final CloseableHttpClient httpClient;

    /**
     * Constructs a client pointing at {@link #DEFAULT_BASE_URL}.
     *
     * @param apiKey the tenant's X-API-Key
     */
    public NotifierApi(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    /**
     * Constructs a client with a custom base URL (e.g. a staging environment).
     *
     * @param apiKey  the tenant's X-API-Key
     * @param baseUrl base URL without a trailing slash, e.g. {@code https://notifier-api.vibe.ir}
     */
    public NotifierApi(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_SOCKET_TIMEOUT_MS, DEFAULT_MAX_CONNECTIONS);
    }

    /**
     * Constructs a client with full control over the underlying HTTP transport.
     *
     * @param apiKey               the tenant's X-API-Key
     * @param baseUrl              base URL without a trailing slash
     * @param connectTimeoutMs     TCP connect timeout
     * @param socketTimeoutMs      read/write socket timeout
     * @param maxConnectionsPerRoute max pooled connections per route
     */
    public NotifierApi(String apiKey, String baseUrl, int connectTimeoutMs, int socketTimeoutMs,
                       int maxConnectionsPerRoute) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        String base = Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(maxConnectionsPerRoute);
        cm.setDefaultMaxPerRoute(maxConnectionsPerRoute);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setSocketTimeout(socketTimeoutMs)
                .setConnectionRequestTimeout(DEFAULT_CONN_REQUEST_TIMEOUT_MS)
                .build();

        this.httpClient = HttpClientBuilder.create()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries() // shipping a notification twice is worse than not at all
                .build();
    }

    /** Releases the underlying connection pool. Call when the client is no longer needed. */
    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (IOException ignored) {
            // nothing meaningful to do on close
        }
    }

    // ------------------------------------------------------------------
    // Core send
    // ------------------------------------------------------------------

    /** Sends a body to a recipient on the given channel. */
    public SendResult send(ChannelType type, String recipient, Content content) {
        return send(type, recipient, content, SendOptions.none());
    }

    /** Sends a body to a recipient on the given channel with options. */
    public SendResult send(ChannelType type, String recipient, Content content, SendOptions opts) {
        JsonObject body = buildSendBody(type, recipient, content, opts);
        JsonObject resp = postJson("/v1/notifications", body);
        return SendResult.from(resp);
    }

    /** Convenience: sends a plain body string. */
    public SendResult send(ChannelType type, String recipient, String body) {
        return send(type, recipient, Content.of(body), SendOptions.none());
    }

    /** Convenience: sends a plain body string with options. */
    public SendResult send(ChannelType type, String recipient, String body, SendOptions opts) {
        return send(type, recipient, Content.of(body), opts);
    }

    /**
     * Sends using a template rendered by the backend. Template vars are passed as-is and
     * substituted server-side; the rendered content is returned in the result.
     */
    public SendResult sendTemplate(ChannelType type, String recipient, String templateId,
                                   Map<String, Object> templateVars) {
        return send(type, recipient, Content.of(""),
                SendOptions.builder().templateId(templateId).templateVars(templateVars).build());
    }

    /** Sends using a template with extra options. */
    public SendResult sendTemplate(ChannelType type, String recipient, String templateId,
                                   Map<String, Object> templateVars, SendOptions opts) {
        SendOptions.Builder b = SendOptions.builder()
                .templateId(templateId)
                .templateVars(templateVars);
        mergeOptions(b, opts);
        return send(type, recipient, Content.of(""), b.build());
    }

    /**
     * Sends using a template referenced by name (resolved server-side against the tenant's
     * templates for the channel + locale). Useful when the caller only has a template name
     * (e.g. a Kavenegar template). Callers with a template UUID should use {@link #sendTemplate}.
     *
     * <p>The name may be given as {@code "group-name:template-name"} to also route via a channel
     * group by name — the SDK splits it into {@code group_name} + {@code template_name}.</p>
     */
    public SendResult sendTemplateByName(ChannelType type, String recipient, String templateName,
                                         Map<String, Object> templateVars) {
        return send(type, recipient, Content.of(""),
                SendOptions.builder().templateName(templateName).templateVars(templateVars).build());
    }

    /**
     * Sends using a template by name with extra options. The name may be
     * {@code "group-name:template-name"} to route via a channel group by name.
     */
    public SendResult sendTemplateByName(ChannelType type, String recipient, String templateName,
                                         Map<String, Object> templateVars, SendOptions opts) {
        SendOptions.Builder b = SendOptions.builder()
                .templateName(templateName)
                .templateVars(templateVars);
        mergeOptions(b, opts);
        return send(type, recipient, Content.of(""), b.build());
    }

    /**
     * Sends using a template by name with ordered values bound POSITIONALLY to the template's
     * declared {@code param_names}. The caller passes values in order — no need to know the
     * placeholder names in the template body. This is the recommended path for Kavenegar migratees.
     *
     * <pre>{@code api.sendTemplateByName(ChannelType.SMS, phone, "betaauth", "123456");}</pre>
     */
    public SendResult sendTemplateByName(ChannelType type, String recipient, String templateName, Object... params) {
        return send(type, recipient, Content.of(""),
                SendOptions.builder().templateName(templateName).templateParams(toList(params)).build());
    }

    /** Sends using a template by name with ordered positional params plus extra options. */
    public SendResult sendTemplateByName(ChannelType type, String recipient, String templateName,
                                         SendOptions opts, Object... params) {
        SendOptions.Builder b = SendOptions.builder()
                .templateName(templateName)
                .templateParams(toList(params));
        mergeOptions(b, opts);
        return send(type, recipient, Content.of(""), b.build());
    }

    /** Sends using a template by id with ordered values bound positionally to its param_names. */
    public SendResult sendTemplate(ChannelType type, String recipient, String templateId, Object... params) {
        return send(type, recipient, Content.of(""),
                SendOptions.builder().templateId(templateId).templateParams(toList(params)).build());
    }

    /** Sends using a template by id with ordered positional params plus extra options. */
    public SendResult sendTemplate(ChannelType type, String recipient, String templateId,
                                   SendOptions opts, Object... params) {
        SendOptions.Builder b = SendOptions.builder()
                .templateId(templateId)
                .templateParams(toList(params));
        mergeOptions(b, opts);
        return send(type, recipient, Content.of(""), b.build());
    }

    private static List<Object> toList(Object... params) {
        return params == null ? Collections.emptyList() : Arrays.asList(params);
    }

    private static void mergeOptions(SendOptions.Builder b, SendOptions o) {
        if (o.getChannelId() != null) b.channelId(o.getChannelId());
        if (o.getGroupId() != null) b.groupId(o.getGroupId());
        if (o.getGroupName() != null) b.groupName(o.getGroupName());
        if (o.getIdempotencyKey() != null) b.idempotencyKey(o.getIdempotencyKey());
        if (o.getSendAt() != null) b.sendAt(o.getSendAt());
        if (o.getCallbackUrl() != null) b.callbackUrl(o.getCallbackUrl());
        if (o.getMetadata() != null) b.metadata(o.getMetadata());
        if (o.getLocale() != null) b.locale(o.getLocale());
    }

    // ------------------------------------------------------------------
    // Bulk send
    // ------------------------------------------------------------------

    /** Sends many recipients in one request (backend fan-out per message). */
    public BulkResult bulk(ChannelType type, List<RecipientMessage> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("channel_type", type.getValue());
        JsonArray arr = new JsonArray();
        for (RecipientMessage m : messages) {
            JsonObject jo = new JsonObject();
            jo.addProperty("recipient", m.getRecipient());
            if (m.getContent() != null) {
                if (m.getContent().getSubject() != null) jo.addProperty("subject", m.getContent().getSubject());
                if (m.getContent().getBody() != null && !m.getContent().getBody().isEmpty()) {
                    jo.addProperty("body", m.getContent().getBody());
                }
            }
            if (m.getTemplateId() != null) jo.addProperty("template_id", m.getTemplateId());
            if (m.getTemplateName() != null) {
                // Support "group-name:template-name" shorthand for group routing by name.
                String[] gt = splitGroup(m.getTemplateName());
                if (gt[0] != null) jo.addProperty("group_name", gt[0]);
                jo.addProperty("template_name", gt[1]);
            }
            if (m.getTemplateVars() != null && !m.getTemplateVars().isEmpty()) {
                jo.add("template_vars", GSON.toJsonTree(m.getTemplateVars()));
            }
            if (m.getCallbackUrl() != null) jo.addProperty("callback_url", m.getCallbackUrl());
            if (m.getMetadata() != null && !m.getMetadata().isEmpty()) {
                jo.add("metadata", GSON.toJsonTree(m.getMetadata()));
            }
            arr.add(jo);
        }
        body.add("messages", arr);

        JsonObject resp = postJson("/v1/notifications/bulk", body);
        String batchId = resp.has("batch_id") ? resp.get("batch_id").getAsString() : null;
        int count = resp.has("count") ? resp.get("count").getAsInt() : 0;
        return new BulkResult(batchId, count);
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /** Fetches the current delivery status of a notification by its UUID ({@link SendResult#getId()}). */
    public StatusResult status(String notificationId) {
        Objects.requireNonNull(notificationId, "notificationId");
        JsonObject resp = getJson("/v1/notifications/" + notificationId);
        return StatusResult.from(resp);
    }

    /**
     * Fetches the delivery status of a notification by the provider message id (e.g. the
     * Kavenegar {@code messageid} / SMS.ir {@code messageId}) rather than the Notifier UUID.
     * This supports the Kavenegar-compatible {@link KavenegarApi#status(Long)} facade.
     */
    public StatusResult statusByProviderMessageId(String providerMessageId) {
        Objects.requireNonNull(providerMessageId, "providerMessageId");
        JsonObject resp = getJson("/v1/notifications/by-provider-message-id/" + providerMessageId);
        return StatusResult.from(resp);
    }

    /**
     * Fetches paginated notification history for the tenant against the filters in the query.
     * Returns a page of items plus an opaque {@code next_cursor} to pass to the next call.
     */
    public HistoryPage listNotifications(HistoryQuery query) {
        StringBuilder path = new StringBuilder("/v1/notifications");
        StringBuilder qs = new StringBuilder();
        HistoryQuery q = query == null ? HistoryQuery.none() : query;
        if (q.getLimit() > 0) appendQuery(qs, "limit", String.valueOf(q.getLimit()));
        if (q.getAfter() != null) appendQuery(qs, "after", q.getAfter());
        if (q.getStatus() != null) appendQuery(qs, "status", q.getStatus());
        if (q.getChannelType() != null) appendQuery(qs, "channel_type", q.getChannelType().getValue());
        if (q.getFrom() != null) appendQuery(qs, "from", q.getFrom());
        if (q.getTo() != null) appendQuery(qs, "to", q.getTo());
        if (q.getExtra() != null) {
            for (Map.Entry<String, String> e : q.getExtra().entrySet()) {
                appendQuery(qs, e.getKey(), e.getValue());
            }
        }
        if (qs.length() > 0) path.append('?').append(qs);

        JsonObject resp = getJson(path.toString());
        List<SendResult> items = new java.util.ArrayList<>();
        if (resp.has("items") && resp.get("items").isJsonArray()) {
            for (JsonElement el : resp.getAsJsonArray("items")) {
                if (el.isJsonObject()) {
                    items.add(SendResult.from(el.getAsJsonObject()));
                }
            }
        }
        String next = resp.has("next_cursor") && !resp.get("next_cursor").isJsonNull()
                ? resp.get("next_cursor").getAsString() : null;
        return new HistoryPage(items, next);
    }

    private static void appendQuery(StringBuilder qs, String name, String value) {
        if (value == null || value.isEmpty()) return;
        if (qs.length() > 0) qs.append('&');
        try {
            qs.append(java.net.URLEncoder.encode(name, "UTF-8"))
              .append('=')
              .append(java.net.URLEncoder.encode(value, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            qs.append(name).append('=').append(value);
        }
    }

    // ------------------------------------------------------------------
    // Typed channel accessors (optional). All channels also work via the generic
    // core send() methods, so these are conveniences, not requirements.
    // ------------------------------------------------------------------

    public EmailChannelApi email() {
        return new EmailChannelApi(this);
    }

    public TelegramChannelApi telegram() {
        return new TelegramChannelApi(this);
    }

    public SlackChannelApi slack() {
        return new SlackChannelApi(this);
    }

    public BaleChannelApi bale() {
        return new BaleChannelApi(this);
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private JsonObject buildSendBody(ChannelType type, String recipient, Content content, SendOptions o) {
        JsonObject body = new JsonObject();
        body.addProperty("channel_type", type.getValue());
        body.addProperty("recipient", recipient);
        if (content != null) {
            if (content.getSubject() != null) body.addProperty("subject", content.getSubject());
            body.addProperty("body", content.getBody());
        }
        if (o.getChannelId() != null) body.addProperty("channel_id", o.getChannelId());
        if (o.getGroupId() != null) body.addProperty("group_id", o.getGroupId());

        // A template may be given as "group-name:template-name" to route via a channel group
        // by name. Split it here so every send-using-a-template path supports the shorthand.
        // An explicit groupName on the options wins over the prefix in the template name.
        String groupName = o.getGroupName();
        String templateName = o.getTemplateName();
        if (templateName != null) {
            String[] gt = splitGroup(templateName);
            if (gt[0] != null) {
                if (groupName == null) groupName = gt[0];
                templateName = gt[1];
            }
        }
        if (groupName != null) body.addProperty("group_name", groupName);
        if (o.getTemplateId() != null) body.addProperty("template_id", o.getTemplateId());
        if (templateName != null) body.addProperty("template_name", templateName);
        if (o.getTemplateVars() != null && !o.getTemplateVars().isEmpty()) {
            body.add("template_vars", GSON.toJsonTree(o.getTemplateVars()));
        }
        if (o.getTemplateParams() != null && !o.getTemplateParams().isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Object p : o.getTemplateParams()) {
                arr.add(GSON.toJsonTree(p));
            }
            body.add("template_params", arr);
        }
        if (o.getIdempotencyKey() != null) body.addProperty("idempotency_key", o.getIdempotencyKey());
        if (o.getSendAt() != null) body.addProperty("send_at", o.getSendAt());
        if (o.getCallbackUrl() != null) body.addProperty("callback_url", o.getCallbackUrl());
        if (o.getMetadata() != null && !o.getMetadata().isEmpty()) {
            body.add("metadata", GSON.toJsonTree(o.getMetadata()));
        }
        if (o.getLocale() != null) body.addProperty("locale", o.getLocale());

        // At least one of body / template_id / template_name must be present.
        boolean missingContent = !body.has("body") || isBlank(body.get("body").getAsString());
        if (missingContent && !body.has("template_id") && !body.has("template_name")) {
            throw new NotifierException("body, template_id, or template_name is required");
        }
        return body;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Splits a "group-name:template-name" string into {group, template}. No (or malformed)
     *  ':' yields {null, template} — so plain template names pass through untouched. */
    private static String[] splitGroup(String template) {
        if (template != null) {
            int idx = template.indexOf(':');
            if (idx > 0 && idx < template.length() - 1) {
                return new String[]{template.substring(0, idx), template.substring(idx + 1)};
            }
        }
        return new String[]{null, template};
    }

    private JsonObject postJson(String path, JsonObject body) {
        HttpPost post = new HttpPost(baseUrl + path);
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Accept", "application/json");
        post.setHeader("X-API-Key", apiKey);
        post.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));
        return execute(post);
    }

    private JsonObject getJson(String path) {
        HttpGet get = new HttpGet(baseUrl + path);
        get.setHeader("Accept", "application/json");
        get.setHeader("X-API-Key", apiKey);
        return execute(get);
    }

    private JsonObject execute(HttpUriRequest request) {
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int code = response.getStatusLine().getStatusCode();
            String raw = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            JsonElement parsed = raw == null || raw.isEmpty() ? null : GSON.fromJson(raw, JsonObject.class);

            if (code < 200 || code >= 300) {
                throw toException(code, parsed);
            }
            if (parsed == null || !parsed.isJsonObject()) {
                throw new NotifierException("Unexpected response from Notifier API: " + raw);
            }
            return parsed.getAsJsonObject();
        } catch (IOException e) {
            throw new HttpException("HTTP request failed: " + e.getMessage(), 0);
        }
    }

    /**
     * Maps a non-2xx response to the appropriate exception. If the body carries a Notifier
     * error ({@code error} field), an {@link ApiException} is thrown with message set to the
     * error text, and code set to the machine {@code code} when it is numeric, else the HTTP
     * status. Transport-level failures (no body) become an {@link HttpException}.
     */
    private NotifierException toException(int code, JsonElement parsed) {
        String message = "HTTP " + code;
        int businessCode = 0;
        boolean hasError = false;
        if (parsed != null && parsed.isJsonObject()) {
            JsonObject obj = parsed.getAsJsonObject();
            if (obj.has("error")) {
                message = obj.get("error").getAsString();
                hasError = true;
            }
            if (obj.has("code")) {
                businessCode = parseErrorCode(obj.get("code"));
            }
        }
        if (hasError) {
            return new ApiException(message, businessCode != 0 ? businessCode : code);
        }
        return new HttpException(message, code);
    }

    private static int parseErrorCode(JsonElement code) {
        if (code == null || code.isJsonNull()) return 0;
        if (code.isJsonPrimitive()) {
            try {
                return code.getAsInt();
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0;
    }
}
