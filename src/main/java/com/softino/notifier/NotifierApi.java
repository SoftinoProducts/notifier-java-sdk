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
import com.softino.notifier.exception.NotifierClosedException;
import com.softino.notifier.exception.NotifierException;
import com.softino.notifier.exception.NotifierQueueFullException;
import com.softino.notifier.internal.AsyncSendEngine;
import com.softino.notifier.internal.RequestSpec;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
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
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>Web Push sends target a registered device id, not the browser's subscription: register the
 * subscription once with {@link #registerPushDevice(PushSubscription)} and send to the returned id
 * with {@link #sendToDevice(String, String)}. The browser subscribes with
 * {@link #getVapidPublicKey()} as its {@code applicationServerKey}.</p>
 *
 * <p>For a Kavenegar-java drop-in facade, see
 * {@link com.softino.notifier.kavenegar.KavenegarApi}.</p>
 *
 * <h2>Blocking and non-blocking sends</h2>
 *
 * <p>The original {@code send*}/{@code status} methods are blocking and unchanged: they return
 * after the hub answers. The {@code send*Async} methods return a {@link java.util.concurrent.CompletableFuture}
 * as soon as the task is queued; {@code send*AndForget} returns immediately and does not expose
 * a result. Neither variant does network I/O on the calling thread, so a slow or unavailable
 * hub cannot hold an application request thread.</p>
 *
 * <p>Async callbacks and listener invocations run on SDK-owned daemon threads. Do not block in
 * {@code thenApply}/{@code thenAccept}: a dependent stage runs on the same reactor thread that
 * completed the send.</p>
 *
 * <p><b>What this does not change:</b> delivery to the handset is still asynchronous and
 * provider-dependent. The SDK removes waiting and thread occupancy on the caller side, not the
 * time it takes a code to reach a phone.</p>
 *
 * <p>Requires Java 8 or newer. The async transport is Apache HttpAsyncClient 4.x (NIO); no
 * {@code ForkJoinPool.commonPool} and no thread-per-message path is used.</p>
 */
public class NotifierApi implements AutoCloseable {

    public static final String DEFAULT_BASE_URL = "https://notifier-api.vibe.ir";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // Legacy blocking defaults. Their values are unchanged from 1.1.x; the async path has its
    // own, user-facing defaults on NotifierConfig.
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = NotifierConfig.DEFAULT_BLOCKING_CONNECT_TIMEOUT_MS;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = NotifierConfig.DEFAULT_BLOCKING_READ_TIMEOUT_MS;
    private static final int DEFAULT_MAX_CONNECTIONS = NotifierConfig.DEFAULT_MAX_CONNECTIONS;

    private final NotifierConfig config;
    private final String apiKey;
    private final String baseUrl;
    private final CloseableHttpClient httpClient;

    /**
     * Created on the first async/forget call so a blocking-only consumer never pays for the
     * NIO reactor or dispatcher threads. Created lock-free; a losing racer is closed.
     */
    private final AtomicReference<AsyncSendEngine> asyncEngine = new AtomicReference<AsyncSendEngine>();

    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread shutdownHook;

    /** Returns the canonical base URL this client targets. */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** The immutable configuration this client was built with. */
    public NotifierConfig getConfig() {
        return config;
    }

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
     * Constructs a client with full control over the legacy blocking transport. Async sends
     * still use the async defaults (connect 2s, read 5s, deadline 10s); use
     * {@link #NotifierApi(NotifierConfig)} to tune both.
     *
     * @param apiKey               the tenant's X-API-Key
     * @param baseUrl              base URL without a trailing slash
     * @param connectTimeoutMs     TCP connect timeout of the blocking transport
     * @param socketTimeoutMs      read/write socket timeout of the blocking transport
     * @param maxConnectionsPerRoute max pooled connections per route
     */
    public NotifierApi(String apiKey, String baseUrl, int connectTimeoutMs, int socketTimeoutMs,
                       int maxConnectionsPerRoute) {
        this(apiKey, baseUrl, connectTimeoutMs, socketTimeoutMs, maxConnectionsPerRoute, false);
    }

    /**
     * Constructs a client, optionally permitting a plaintext base URL on a remote host.
     *
     * <p>The API key travels in the {@code X-API-Key} header on every request, so a
     * plaintext target exposes it to anything on the network path. An {@code http://} URL is
     * therefore refused unless the host is loopback; pass {@code allowInsecureBaseUrl=true}
     * to opt out deliberately for a trusted local or in-cluster target.
     */
    public NotifierApi(String apiKey, String baseUrl, int connectTimeoutMs, int socketTimeoutMs,
                       int maxConnectionsPerRoute, boolean allowInsecureBaseUrl) {
        this(NotifierConfig.legacy(apiKey, baseUrl, connectTimeoutMs, socketTimeoutMs,
                maxConnectionsPerRoute, allowInsecureBaseUrl).build());
    }

    /**
     * Constructs a client from full configuration, covering the blocking and non-blocking
     * paths plus queue, retry, deadline, listener and shutdown settings.
     */
    public NotifierApi(NotifierConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.apiKey = Objects.requireNonNull(config.getApiKey(), "apiKey");
        String base = config.getBaseUrl();
        requireSecureBaseUrl(base, config.isAllowInsecureBaseUrl());
        this.baseUrl = base;

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(config.getMaxConnections());
        cm.setDefaultMaxPerRoute(config.getMaxConnections());

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(config.getBlockingConnectTimeoutMs())
                .setSocketTimeout(config.getBlockingReadTimeoutMs())
                .setConnectionRequestTimeout(config.getConnectionRequestTimeoutMs())
                .build();

        this.httpClient = HttpClientBuilder.create()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries() // shipping a notification twice is worse than not at all
                .build();

        Thread hook = null;
        if (config.isRegisterShutdownHook()) {
            hook = new Thread(new Runnable() {
                @Override
                public void run() {
                    NotifierApi.this.close();
                }
            }, "notifier-shutdown-hook");
            try {
                Runtime.getRuntime().addShutdownHook(hook);
            } catch (IllegalStateException alreadyShuttingDown) {
                hook = null;
            }
        }
        this.shutdownHook = hook;
    }

    /**
     * Releases the async engine (draining accepted sends for the configured window) and the
     * blocking connection pool. Idempotent; a closed client rejects new sends with
     * {@link NotifierClosedException}.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        AsyncSendEngine engine = asyncEngine.get();
        if (engine != null) {
            engine.close();
        }
        try {
            httpClient.close();
        } catch (IOException ignored) {
            // nothing meaningful to do on close
        }
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // already shutting down; the hook is either running or irrelevant
            }
        }
    }

    /** True once {@link #close()} has been called. */
    public boolean isClosed() {
        return closed.get();
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
        if (o.getRouteName() != null) b.routeName(o.getRouteName());
        if (o.getIdempotencyKey() != null) b.idempotencyKey(o.getIdempotencyKey());
        if (o.getSendAt() != null) b.sendAt(o.getSendAt());
        if (o.getCallbackUrl() != null) b.callbackUrl(o.getCallbackUrl());
        if (o.getMetadata() != null) b.metadata(o.getMetadata());
        if (o.getLocale() != null) b.locale(o.getLocale());
        if (o.isSimulated()) b.simulated(true);
        // Async-only fields. The blocking path ignores them, but template overloads must not
        // drop them when they are used with the async entry points.
        if (o.getDeadline() != null) b.deadline(o.getDeadline());
        if (o.getConnectTimeout() != null) b.connectTimeout(o.getConnectTimeout());
        if (o.getReadTimeout() != null) b.readTimeout(o.getReadTimeout());
        if (o.getTraceId() != null) b.traceId(o.getTraceId());
        if (o.getPriority() != null) b.priority(o.getPriority());
        if (o.getMaxRetries() != null) b.maxRetries(o.getMaxRetries());
        if (o.isFireAndForget()) b.fireAndForget(true);
    }

    // ------------------------------------------------------------------
    // Non-blocking send API
    //
    // The calling thread only builds a small task object and offers it to a bounded lane;
    // request encoding, connection setup, TLS, writes, reads, retries and response parsing all
    // happen on SDK-owned daemon threads. *Async returns a future that is completed on an SDK
    // thread; *AndForget returns immediately and throws only the two immediate local failures:
    // NotifierQueueFullException and NotifierClosedException.
    // ------------------------------------------------------------------

    /** Non-blocking {@link #send(ChannelType, String, Content)}. */
    public CompletableFuture<SendResult> sendAsync(ChannelType type, String recipient, Content content) {
        return sendAsync(type, recipient, content, SendOptions.none());
    }

    /** Non-blocking send with options; returns after the task is queued, not after the hub replies. */
    public CompletableFuture<SendResult> sendAsync(ChannelType type, String recipient, Content content,
                                                   SendOptions opts) {
        SendOptions o = opts == null ? SendOptions.none() : opts;
        return asyncSend(type, recipient, content, o, SendPriority.BULK);
    }

    /** Non-blocking {@link #send(ChannelType, String, String)}. */
    public CompletableFuture<SendResult> sendAsync(ChannelType type, String recipient, String body) {
        return sendAsync(type, recipient, Content.of(body), SendOptions.none());
    }

    /** Non-blocking plain-body send with options. */
    public CompletableFuture<SendResult> sendAsync(ChannelType type, String recipient, String body,
                                                   SendOptions opts) {
        return sendAsync(type, recipient, Content.of(body), opts);
    }

    /**
     * Fire-and-forget send: returns as soon as the task is queued and never throws a network
     * failure. Failure is visible through {@link #stats()} ({@code dropped}) and the
     * {@link SendListener} passed to {@link NotifierConfig}.
     *
     * @throws NotifierQueueFullException when the lane's bounded queue is full (nothing is sent)
     * @throws NotifierClosedException    when the client is closed
     */
    public void sendAndForget(ChannelType type, String recipient, Content content)
            throws NotifierQueueFullException, NotifierClosedException {
        sendAndForget(type, recipient, content, SendOptions.none());
    }

    /** Fire-and-forget send with options. */
    public void sendAndForget(ChannelType type, String recipient, Content content, SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        SendOptions o = opts == null ? SendOptions.none() : opts;
        asyncSendForget(type, recipient, content, o, SendPriority.BULK);
    }

    /** Fire-and-forget plain-body send. */
    public void sendAndForget(ChannelType type, String recipient, String body)
            throws NotifierQueueFullException, NotifierClosedException {
        sendAndForget(type, recipient, Content.of(body), SendOptions.none());
    }

    /** Fire-and-forget plain-body send with options. */
    public void sendAndForget(ChannelType type, String recipient, String body, SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        sendAndForget(type, recipient, Content.of(body), opts);
    }

    /** Non-blocking template-by-id send (values by name). */
    public CompletableFuture<SendResult> sendTemplateAsync(ChannelType type, String recipient,
                                                           String templateId, Map<String, Object> templateVars) {
        return sendTemplateAsync(type, recipient, templateId, templateVars, SendOptions.none());
    }

    /** Non-blocking template-by-id send with options. */
    public CompletableFuture<SendResult> sendTemplateAsync(ChannelType type, String recipient,
                                                           String templateId, Map<String, Object> templateVars,
                                                           SendOptions opts) {
        SendOptions.Builder b = SendOptions.builder().templateId(templateId).templateVars(templateVars);
        mergeOptions(b, opts == null ? SendOptions.none() : opts);
        return sendAsync(type, recipient, Content.of(""), b.build());
    }

    /** Non-blocking template-by-id send (values positionally). */
    public CompletableFuture<SendResult> sendTemplateAsync(ChannelType type, String recipient,
                                                           String templateId, Object... params) {
        return sendTemplateAsync(type, recipient, templateId, SendOptions.none(), params);
    }

    /** Non-blocking template-by-id send with options (values positionally). */
    public CompletableFuture<SendResult> sendTemplateAsync(ChannelType type, String recipient,
                                                           String templateId, SendOptions opts,
                                                           Object... params) {
        SendOptions.Builder b = SendOptions.builder().templateId(templateId).templateParams(toList(params));
        mergeOptions(b, opts == null ? SendOptions.none() : opts);
        return sendAsync(type, recipient, Content.of(""), b.build());
    }

    /** Fire-and-forget template-by-id send (values by name). */
    public void sendTemplateAndForget(ChannelType type, String recipient, String templateId,
                                      Map<String, Object> templateVars)
            throws NotifierQueueFullException, NotifierClosedException {
        sendTemplateAndForget(type, recipient, templateId, templateVars, SendOptions.none());
    }

    /** Fire-and-forget template-by-id send with options (values by name). */
    public void sendTemplateAndForget(ChannelType type, String recipient, String templateId,
                                      Map<String, Object> templateVars, SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        SendOptions.Builder b = SendOptions.builder().templateId(templateId).templateVars(templateVars);
        mergeOptions(b, opts == null ? SendOptions.none() : opts);
        sendAndForget(type, recipient, Content.of(""), b.build());
    }

    /** Fire-and-forget template-by-id send (values positionally). */
    public void sendTemplateAndForget(ChannelType type, String recipient, String templateId,
                                      Object... params)
            throws NotifierQueueFullException, NotifierClosedException {
        sendTemplateAndForget(type, recipient, templateId, SendOptions.none(), params);
    }

    /** Fire-and-forget template-by-id send with options (values positionally). */
    public void sendTemplateAndForget(ChannelType type, String recipient, String templateId,
                                      SendOptions opts, Object... params)
            throws NotifierQueueFullException, NotifierClosedException {
        SendOptions.Builder b = SendOptions.builder().templateId(templateId).templateParams(toList(params));
        mergeOptions(b, opts == null ? SendOptions.none() : opts);
        sendAndForget(type, recipient, Content.of(""), b.build());
    }

    /** Non-blocking template-by-name send (values by name). */
    public CompletableFuture<SendResult> sendTemplateByNameAsync(ChannelType type, String recipient,
                                                                 String templateName,
                                                                 Map<String, Object> templateVars) {
        return sendTemplateByNameAsync(type, recipient, templateName, templateVars, SendOptions.none());
    }

    /** Non-blocking template-by-name send with options (values by name). */
    public CompletableFuture<SendResult> sendTemplateByNameAsync(ChannelType type, String recipient,
                                                                 String templateName,
                                                                 Map<String, Object> templateVars,
                                                                 SendOptions opts) {
        SendOptions.Builder b = SendOptions.builder().templateName(templateName).templateVars(templateVars);
        mergeOptions(b, opts == null ? SendOptions.none() : opts);
        return sendAsync(type, recipient, Content.of(""), b.build());
    }

    /** Non-blocking template-by-name send (values positionally). */
    public CompletableFuture<SendResult> sendTemplateByNameAsync(ChannelType type, String recipient,
                                                                 String templateName, Object... params) {
        return sendTemplateByNameAsync(type, recipient, templateName, SendOptions.none(), params);
    }

    /** Non-blocking template-by-name send with options (values positionally). */
    public CompletableFuture<SendResult> sendTemplateByNameAsync(ChannelType type, String recipient,
                                                                 String templateName, SendOptions opts,
                                                                 Object... params) {
        SendOptions.Builder b = SendOptions.builder().templateName(templateName).templateParams(toList(params));
        mergeOptions(b, opts == null ? SendOptions.none() : opts);
        return sendAsync(type, recipient, Content.of(""), b.build());
    }

    /** Fire-and-forget template-by-name send (values by name). */
    public void sendTemplateByNameAndForget(ChannelType type, String recipient, String templateName,
                                            Map<String, Object> templateVars)
            throws NotifierQueueFullException, NotifierClosedException {
        sendTemplateByNameAndForget(type, recipient, templateName, templateVars, SendOptions.none());
    }

    /** Fire-and-forget template-by-name send with options (values by name). */
    public void sendTemplateByNameAndForget(ChannelType type, String recipient, String templateName,
                                            Map<String, Object> templateVars, SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        SendOptions.Builder b = SendOptions.builder().templateName(templateName).templateVars(templateVars);
        mergeOptions(b, opts == null ? SendOptions.none() : opts);
        sendAndForget(type, recipient, Content.of(""), b.build());
    }

    /** Fire-and-forget template-by-name send (values positionally). */
    public void sendTemplateByNameAndForget(ChannelType type, String recipient, String templateName,
                                            Object... params)
            throws NotifierQueueFullException, NotifierClosedException {
        sendTemplateByNameAndForget(type, recipient, templateName, SendOptions.none(), params);
    }

    /** Fire-and-forget template-by-name send with options (values positionally). */
    public void sendTemplateByNameAndForget(ChannelType type, String recipient, String templateName,
                                            SendOptions opts, Object... params)
            throws NotifierQueueFullException, NotifierClosedException {
        SendOptions.Builder b = SendOptions.builder().templateName(templateName).templateParams(toList(params));
        mergeOptions(b, opts == null ? SendOptions.none() : opts);
        sendAndForget(type, recipient, Content.of(""), b.build());
    }

    /** Non-blocking bulk send (future completes after the hub accepts the batch). */
    public CompletableFuture<BulkResult> bulkAsync(ChannelType type, List<RecipientMessage> messages) {
        return bulkAsync(type, messages, false);
    }

    /** Non-blocking bulk send, optionally rehearsed. */
    public CompletableFuture<BulkResult> bulkAsync(final ChannelType type,
                                                   final List<RecipientMessage> messages,
                                                   final boolean simulated) {
        return bulkAsync(type, messages, simulated, SendOptions.none());
    }

    /** Non-blocking bulk send with options (deadline, trace id, priority, retry cap). */
    public CompletableFuture<BulkResult> bulkAsync(final ChannelType type,
                                                   final List<RecipientMessage> messages,
                                                   final boolean simulated, SendOptions opts) {
        final SendOptions effective = opts == null ? SendOptions.none() : opts;
        try {
            return asyncEngine().submit(new RequestSpec.Factory<BulkResult>() {
                @Override
                public RequestSpec<BulkResult> create(String idempotencyKey) {
                    return RequestSpec.<BulkResult>builder("POST", "/v1/notifications/bulk", BULK_PARSER)
                            .body(buildBulkBody(type, messages, simulated).toString())
                            .idempotencyKey(idempotencyKey)
                            .channelType(type == null ? null : type.getValue())
                            .build();
                }
            }, effective, SendPriority.BULK, true, true);
        } catch (NotifierException e) {
            return failedFuture(e);
        }
    }

    /** Fire-and-forget bulk send. */
    public void bulkAndForget(ChannelType type, List<RecipientMessage> messages)
            throws NotifierQueueFullException, NotifierClosedException {
        bulkAndForget(type, messages, false);
    }

    /** Fire-and-forget bulk send, optionally rehearsed. */
    public void bulkAndForget(final ChannelType type, final List<RecipientMessage> messages,
                              final boolean simulated)
            throws NotifierQueueFullException, NotifierClosedException {
        bulkAndForget(type, messages, simulated, SendOptions.none());
    }

    /** Fire-and-forget bulk send with options. */
    public void bulkAndForget(final ChannelType type, final List<RecipientMessage> messages,
                              final boolean simulated, SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        final SendOptions effective = opts == null ? SendOptions.none() : opts;
        asyncEngine().submitAndForget(new RequestSpec.Factory<BulkResult>() {
            @Override
            public RequestSpec<BulkResult> create(String idempotencyKey) {
                return RequestSpec.<BulkResult>builder("POST", "/v1/notifications/bulk", BULK_PARSER)
                        .body(buildBulkBody(type, messages, simulated).toString())
                        .idempotencyKey(idempotencyKey)
                        .channelType(type == null ? null : type.getValue())
                        .build();
            }
        }, effective, SendPriority.BULK, true);
    }

    /** Non-blocking status lookup by Notifier UUID. */
    public CompletableFuture<StatusResult> statusAsync(String notificationId) {
        Objects.requireNonNull(notificationId, "notificationId");
        return asyncStatus("/v1/notifications/" + encodePathSegment(notificationId, "notificationId"));
    }

    /** Explicit alias for {@link #statusAsync(String)}: status by Notifier UUID. */
    public CompletableFuture<StatusResult> statusByNotificationIdAsync(String notificationId) {
        return statusAsync(notificationId);
    }

    /** Non-blocking status lookup by provider message id. */
    public CompletableFuture<StatusResult> statusByProviderMessageIdAsync(String providerMessageId) {
        Objects.requireNonNull(providerMessageId, "providerMessageId");
        return asyncStatus("/v1/notifications/by-provider-message-id/"
                + encodePathSegment(providerMessageId, "providerMessageId"));
    }

    /** Non-blocking Web Push send to a registered device. */
    public CompletableFuture<SendResult> sendToDeviceAsync(String deviceId, String subject, String body) {
        return sendToDeviceAsync(deviceId, subject, body, SendOptions.none());
    }

    /** Non-blocking Web Push send to a registered device with options. */
    public CompletableFuture<SendResult> sendToDeviceAsync(final String deviceId, String subject, String body,
                                                           SendOptions opts) {
        SendOptions o = opts == null ? SendOptions.none() : opts;
        return asyncSend(ChannelType.WEBPUSH, new RecipientProvider() {
            @Override
            public String get() {
                return deviceRecipient(deviceId);
            }
        }, new Content(subject, body), o, SendPriority.BULK);
    }

    /** Non-blocking Web Push send to a registered device without a subject. */
    public CompletableFuture<SendResult> sendToDeviceAsync(String deviceId, String body) {
        return sendToDeviceAsync(deviceId, null, body);
    }

    /** Non-blocking Web Push send to a registered device without a subject, with options. */
    public CompletableFuture<SendResult> sendToDeviceAsync(String deviceId, String body, SendOptions opts) {
        return sendToDeviceAsync(deviceId, null, body, opts);
    }

    /** Non-blocking templated Web Push send to a registered device. */
    public CompletableFuture<SendResult> sendTemplateToDeviceAsync(String deviceId, String templateName,
                                                                   Object... params) {
        return sendTemplateToDeviceAsync(deviceId, templateName, SendOptions.none(), params);
    }

    /** Non-blocking templated Web Push send to a registered device with options. */
    public CompletableFuture<SendResult> sendTemplateToDeviceAsync(final String deviceId,
                                                                   final String templateName,
                                                                   SendOptions opts, Object... params) {
        SendOptions.Builder b = SendOptions.builder().templateName(templateName).templateParams(toList(params));
        mergeOptions(b, opts == null ? SendOptions.none() : opts);
        return asyncSend(ChannelType.WEBPUSH, new RecipientProvider() {
            @Override
            public String get() {
                return deviceRecipient(deviceId);
            }
        }, Content.of(""), b.build(), SendPriority.BULK);
    }

    /** Fire-and-forget Web Push send to a registered device. */
    public void sendToDeviceAndForget(final String deviceId, final String subject, final String body)
            throws NotifierQueueFullException, NotifierClosedException {
        sendToDeviceAndForget(deviceId, subject, body, SendOptions.none());
    }

    /** Fire-and-forget Web Push send to a registered device with options. */
    public void sendToDeviceAndForget(final String deviceId, final String subject, final String body,
                                      SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        SendOptions o = opts == null ? SendOptions.none() : opts;
        asyncSendForget(ChannelType.WEBPUSH, new RecipientProvider() {
            @Override
            public String get() {
                return deviceRecipient(deviceId);
            }
        }, new Content(subject, body), o, SendPriority.BULK);
    }

    /** Fire-and-forget Web Push send to a registered device without a subject. */
    public void sendToDeviceAndForget(String deviceId, String body)
            throws NotifierQueueFullException, NotifierClosedException {
        sendToDeviceAndForget(deviceId, null, body);
    }

    /** Fire-and-forget templated Web Push send to a registered device. */
    public void sendTemplateToDeviceAndForget(String deviceId, String templateName, Object... params)
            throws NotifierQueueFullException, NotifierClosedException {
        sendTemplateToDeviceAndForget(deviceId, templateName, SendOptions.none(), params);
    }

    /** Fire-and-forget templated Web Push send to a registered device with options. */
    public void sendTemplateToDeviceAndForget(final String deviceId, final String templateName,
                                              SendOptions opts, Object... params)
            throws NotifierQueueFullException, NotifierClosedException {
        SendOptions.Builder b = SendOptions.builder().templateName(templateName).templateParams(toList(params));
        mergeOptions(b, opts == null ? SendOptions.none() : opts);
        asyncSendForget(ChannelType.WEBPUSH, new RecipientProvider() {
            @Override
            public String get() {
                return deviceRecipient(deviceId);
            }
        }, Content.of(""), b.build(), SendPriority.BULK);
    }

    private CompletableFuture<StatusResult> asyncStatus(String path) {
        try {
            return asyncEngine().submit(new RequestSpec.Factory<StatusResult>() {
                @Override
                public RequestSpec<StatusResult> create(String ignored) {
                    return RequestSpec.<StatusResult>builder("GET", path, STATUS_PARSER).build();
                }
            }, SendOptions.none(), SendPriority.BULK, false, false);
        } catch (NotifierException e) {
            return failedFuture(e);
        }
    }

    /**
     * Counters and gauges for the async send path. Safe to call at any time; a client that has
     * never sent asynchronously reports zeros.
     */
    public NotifierStats stats() {
        AsyncSendEngine engine = asyncEngine.get();
        return engine == null
                ? new NotifierStats(0, 0, 0, 0, 0, 0, 0, 0, 0)
                : engine.stats();
    }

    /**
     * Waits until every accepted async send has reached a terminal outcome, or the timeout
     * expires. Intended for tests, demos and orderly application shutdown; it parks the
     * calling thread deliberately and is never used on a send path.
     */
    public boolean awaitIdle(Duration timeout) {
        AsyncSendEngine engine = asyncEngine.get();
        return engine == null || engine.awaitIdle(timeout);
    }

    private AsyncSendEngine asyncEngine() {
        AsyncSendEngine existing = asyncEngine.get();
        if (existing != null) {
            return existing;
        }
        if (closed.get()) {
            throw new NotifierClosedException("notifier client is closed");
        }
        AsyncSendEngine created = new AsyncSendEngine(config);
        if (closed.get()) {
            created.close();
            throw new NotifierClosedException("notifier client is closed");
        }
        if (!asyncEngine.compareAndSet(null, created)) {
            created.close();
        }
        existing = asyncEngine.get();
        if (closed.get()) {
            AsyncSendEngine engine = asyncEngine.get();
            if (engine != null) {
                engine.close();
            }
            throw new NotifierClosedException("notifier client is closed");
        }
        return existing;
    }

    /** Resolves the recipient on an SDK dispatcher thread, not on the caller. */
    private interface RecipientProvider {
        String get();
    }

    private CompletableFuture<SendResult> asyncSend(ChannelType type, String recipient, Content content,
                                                    SendOptions options, SendPriority priority) {
        return asyncSend(type, constant(recipient), content, options, priority);
    }

    private CompletableFuture<SendResult> asyncSend(ChannelType type, RecipientProvider recipient,
                                                    Content content, SendOptions options,
                                                    SendPriority priority) {
        try {
            return asyncEngine().submit(sendFactory(type, recipient, content, options), options, priority,
                    true, true);
        } catch (NotifierException e) {
            return failedFuture(e);
        }
    }

    private void asyncSendForget(ChannelType type, String recipient, Content content,
                                 SendOptions options, SendPriority priority) {
        asyncSendForget(type, constant(recipient), content, options, priority);
    }

    private void asyncSendForget(ChannelType type, RecipientProvider recipient, Content content,
                                 SendOptions options, SendPriority priority) {
        asyncEngine().submitAndForget(sendFactory(type, recipient, content, options), options, priority, true);
    }

    private static RecipientProvider constant(final String recipient) {
        return new RecipientProvider() {
            @Override
            public String get() {
                return recipient;
            }
        };
    }

    private RequestSpec.Factory<SendResult> sendFactory(final ChannelType type,
                                                        final RecipientProvider recipient,
                                                        final Content content,
                                                        final SendOptions options) {
        return new RequestSpec.Factory<SendResult>() {
            @Override
            public RequestSpec<SendResult> create(String idempotencyKey) {
                // A provider that validates (Web Push device ids) runs here, on a dispatcher:
                // an invalid value becomes a typed failure/dropped send, never a caller throw.
                JsonObject body = buildSendBody(type, recipient.get(), content,
                        options.withIdempotencyKey(idempotencyKey));
                return RequestSpec.<SendResult>builder("POST", "/v1/notifications", SEND_PARSER)
                        .body(body.toString())
                        .idempotencyKey(idempotencyKey)
                        .channelType(type == null ? null : type.getValue())
                        .build();
            }
        };
    }

    private static final RequestSpec.Parser<SendResult> SEND_PARSER =
            new RequestSpec.Parser<SendResult>() {
                @Override
                public SendResult parse(JsonObject body, String idempotencyKey) {
                    if (body == null) {
                        throw new NotifierException("hub returned an empty send response");
                    }
                    return SendResult.from(body).withIdempotencyKey(idempotencyKey);
                }
            };

    private static final RequestSpec.Parser<StatusResult> STATUS_PARSER =
            new RequestSpec.Parser<StatusResult>() {
                @Override
                public StatusResult parse(JsonObject body, String ignored) {
                    if (body == null) {
                        throw new NotifierException("hub returned an empty status response");
                    }
                    return StatusResult.from(body);
                }
            };

    private static final RequestSpec.Parser<BulkResult> BULK_PARSER =
            new RequestSpec.Parser<BulkResult>() {
                @Override
                public BulkResult parse(JsonObject body, String ignored) {
                    if (body == null) {
                        throw new NotifierException("hub returned an empty bulk response");
                    }
                    String batchId = body.has("batch_id") ? body.get("batch_id").getAsString() : null;
                    int count = body.has("count") ? body.get("count").getAsInt() : 0;
                    return new BulkResult(batchId, count);
                }
            };

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(error);
        return future;
    }

    // ------------------------------------------------------------------
    // Bulk send
    // ------------------------------------------------------------------

    /** Sends many recipients in one request (backend fan-out per message). */
    public BulkResult bulk(ChannelType type, List<RecipientMessage> messages) {
        return bulk(type, messages, false);
    }

    /**
     * Sends many recipients in one request. When {@code simulated} is true every message is
     * rehearsed — the whole pipeline runs but the provider is never contacted — unless a message
     * overrides it with {@link RecipientMessage.Builder#simulated(boolean)}.
     */
    public BulkResult bulk(ChannelType type, List<RecipientMessage> messages, boolean simulated) {
        JsonObject resp = postJson("/v1/notifications/bulk", buildBulkBody(type, messages, simulated));
        String batchId = resp.has("batch_id") ? resp.get("batch_id").getAsString() : null;
        int count = resp.has("count") ? resp.get("count").getAsInt() : 0;
        return new BulkResult(batchId, count);
    }

    /** Builds the bulk request body once, for both the blocking and the async bulk paths. */
    private JsonObject buildBulkBody(ChannelType type, List<RecipientMessage> messages, boolean simulated) {
        JsonObject body = new JsonObject();
        body.addProperty("channel_type", type.getValue());
        if (simulated) body.addProperty("simulated", true);
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
                // The batch has one channel_type, so a route (which defines its own
                // channels) is not meaningful here; fail loudly instead of sending to
                // the wrong place.
                Ref ref = parseRef(m.getTemplateName());
                if (ref.route != null) {
                    throw new NotifierException("bulk sends do not support the route: shorthand; use a single send");
                }
                if (ref.group != null) jo.addProperty("group_name", ref.group);
                jo.addProperty("template_name", ref.template);
            }
            if (m.getTemplateVars() != null && !m.getTemplateVars().isEmpty()) {
                jo.add("template_vars", GSON.toJsonTree(m.getTemplateVars()));
            }
            if (m.getCallbackUrl() != null) jo.addProperty("callback_url", m.getCallbackUrl());
            if (m.getMetadata() != null && !m.getMetadata().isEmpty()) {
                jo.add("metadata", GSON.toJsonTree(m.getMetadata()));
            }
            if (m.getSimulated() != null) {
                jo.addProperty("simulated", m.getSimulated());
            }
            arr.add(jo);
        }
        body.add("messages", arr);
        return body;
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /** Fetches the current delivery status of a notification by its UUID ({@link SendResult#getId()}). */
    public StatusResult status(String notificationId) {
        Objects.requireNonNull(notificationId, "notificationId");
        JsonObject resp = getJson("/v1/notifications/" + encodePathSegment(notificationId, "notificationId"));
        return StatusResult.from(resp);
    }

    /**
     * Fetches the delivery status of a notification by the provider message id (e.g. the
     * Kavenegar {@code messageid} / SMS.ir {@code messageId}) rather than the Notifier UUID.
     * This supports the Kavenegar-compatible
     * {@link com.softino.notifier.kavenegar.KavenegarApi#status(long)} facade.
     */
    public StatusResult statusByProviderMessageId(String providerMessageId) {
        Objects.requireNonNull(providerMessageId, "providerMessageId");
        JsonObject resp = getJson("/v1/notifications/by-provider-message-id/"
                + encodePathSegment(providerMessageId, "providerMessageId"));
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
    // Web Push devices
    //
    // A Web Push recipient is a device id returned by registerPushDevice, never the browser's
    // subscription: the subscription's endpoint is a capability, so it is registered once and
    // the service stores it write-only.
    // ------------------------------------------------------------------

    /**
     * Registers a browser push subscription and returns the device to send to.
     *
     * <p>Registration is idempotent per subscription: calling it again with the same endpoint
     * updates that device and returns the same id, so a frontend may call it on every visit. A
     * service with no active vapid channel answers 404; a tenant at its device limit answers
     * 409.</p>
     */
    public PushDevice registerPushDevice(PushSubscription subscription) {
        return registerPushDevice(RegisterPushDeviceRequest.of(subscription));
    }

    /** Registers a browser push subscription under your own user identifier. */
    public PushDevice registerPushDevice(PushSubscription subscription, String userRef) {
        return registerPushDevice(RegisterPushDeviceRequest.builder()
                .subscription(subscription)
                .userRef(userRef)
                .build());
    }

    /**
     * Registers a browser push subscription with full control over the request: an optional
     * {@code userRef} and an optional {@code channelId} pinning the vapid channel.
     *
     * <p>The subscription, its endpoint and both keys ({@code p256dh}, {@code auth}) are required
     * and checked locally, so a malformed registration fails before a request is made.</p>
     */
    public PushDevice registerPushDevice(RegisterPushDeviceRequest request) {
        PushSubscription subscription = request == null ? null : request.getSubscription();
        if (subscription == null) {
            throw new NotifierException("push subscription is required; build it from the browser's "
                    + "pushManager.subscribe() result");
        }
        if (isBlank(subscription.getEndpoint())) {
            throw new NotifierException("push subscription endpoint is required");
        }
        if (subscription.getKeys() == null
                || isBlank(subscription.getKeys().getP256dh())
                || isBlank(subscription.getKeys().getAuth())) {
            throw new NotifierException("push subscription keys (p256dh, auth) are required");
        }

        JsonObject body = new JsonObject();
        // channel_id and user_ref are omitted entirely when blank, as the API expects.
        if (!isBlank(request.getChannelId())) body.addProperty("channel_id", request.getChannelId().trim());
        if (!isBlank(request.getUserRef())) body.addProperty("user_ref", request.getUserRef());
        body.add("subscription", subscription.toJson());

        return PushDevice.from(postJson("/v1/push/devices", body));
    }

    /**
     * Returns the value to use as the {@code recipient} of a webpush send for a registered device,
     * so a caller that needs the generic {@link #send} path (extra options, a channel group) still
     * names the right thing.
     *
     * <p>This is a shape check, not a parse: it rejects a subscription JSON or an {@code https://}
     * endpoint passed where a device id belongs, because that becomes a failed notification row
     * that looks like a provider problem.</p>
     *
     * @throws NotifierException when the value is blank, looks like a subscription, or is not a
     *                           36-character uuid
     */
    public static String deviceRecipient(String deviceId) {
        if (isBlank(deviceId)) {
            throw new NotifierException("push device id is required; register the subscription with "
                    + "registerPushDevice first");
        }
        if (looksLikeSubscription(deviceId)) {
            throw new NotifierException("recipient for the webpush channel is a device id, not a push "
                    + "subscription; register the subscription with registerPushDevice and send to the "
                    + "id it returns");
        }
        if (!looksLikeUuid(deviceId)) {
            throw new NotifierException("push device id \"" + deviceId + "\" is not a uuid; it is the id "
                    + "returned by registerPushDevice");
        }
        return deviceId;
    }

    /** Fetches one registered device by id. */
    public PushDevice getPushDevice(String deviceId) {
        String id = deviceRecipient(deviceId);
        return PushDevice.from(getJson("/v1/push/devices/" + encodePathSegment(id, "deviceId")));
    }

    /** Alias for {@link #getPushDevice(String)}; the Go SDK names the same operation {@code PushDevice}. */
    public PushDevice pushDevice(String deviceId) {
        return getPushDevice(deviceId);
    }

    /** Lists one page of the tenant's registered devices, newest first. */
    public PushDevicePage listPushDevices(PushDeviceQuery query) {
        PushDeviceQuery q = query == null ? PushDeviceQuery.none() : query;
        StringBuilder path = new StringBuilder("/v1/push/devices");
        StringBuilder qs = new StringBuilder();
        if (!isBlank(q.getUserRef())) appendQuery(qs, "user_ref", q.getUserRef());
        if (q.getLimit() > 0) appendQuery(qs, "limit", String.valueOf(q.getLimit()));
        if (q.getOffset() > 0) appendQuery(qs, "offset", String.valueOf(q.getOffset()));
        if (qs.length() > 0) path.append('?').append(qs);

        return PushDevicePage.from(getJson(path.toString()));
    }

    /**
     * Convenience: lists one user's devices. A blank {@code userRef} and non-positive
     * {@code limit}/{@code offset} are omitted rather than sent as zeros.
     */
    public PushDevicePage listPushDevices(String userRef, int limit, int offset) {
        return listPushDevices(new PushDeviceQuery(userRef, limit, offset));
    }

    /**
     * Deletes a device, so it stops receiving notifications. This is the logout path: without it a
     * signed-out browser keeps receiving the previous user's notifications.
     */
    public void deletePushDevice(String deviceId) {
        String id = deviceRecipient(deviceId);
        deleteJson("/v1/push/devices/" + encodePathSegment(id, "deviceId"));
    }

    /**
     * Returns the public key a browser must subscribe with, for the highest-priority active vapid
     * channel.
     */
    public VapidKey getVapidPublicKey() {
        return getVapidPublicKey(null);
    }

    /**
     * Returns the public key a browser must subscribe with, for one vapid channel; a blank
     * {@code channelId} selects the highest-priority active one. Returns 404 when the tenant has
     * no active vapid channel.
     */
    public VapidKey getVapidPublicKey(String channelId) {
        StringBuilder path = new StringBuilder("/v1/push/vapid-public-key");
        if (!isBlank(channelId)) {
            StringBuilder qs = new StringBuilder();
            appendQuery(qs, "channel_id", channelId);
            path.append('?').append(qs);
        }
        return VapidKey.from(getJson(path.toString()));
    }

    /** Sends a plain Web Push notification to a registered device. */
    public SendResult sendToDevice(String deviceId, String subject, String body) {
        return send(ChannelType.WEBPUSH, deviceRecipient(deviceId), new Content(subject, body));
    }

    /** Sends a plain Web Push notification to a registered device without a subject. */
    public SendResult sendToDevice(String deviceId, String body) {
        return sendToDevice(deviceId, null, body);
    }

    /**
     * Renders a template on the service and delivers it to a registered device. The template must
     * exist for the {@code webpush} channel; the values bind positionally to its declared
     * {@code param_names}.
     */
    public SendResult sendTemplateToDevice(String deviceId, String templateName, Object... params) {
        return sendTemplateByName(ChannelType.WEBPUSH, deviceRecipient(deviceId), templateName, params);
    }

    /** Catches a subscription JSON or a push endpoint passed where a device id belongs. */
    private static boolean looksLikeSubscription(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("{") || trimmed.contains("https://");
    }

    /**
     * A uuid shape check (36 characters, dashes at 8/13/18/23). It exists to catch a value of the
     * wrong kind without pulling a uuid dependency into an SDK that otherwise has none.
     */
    private static boolean looksLikeUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            char c = value.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
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
        body.addProperty("recipient", recipient);
        if (content != null) {
            if (content.getSubject() != null) body.addProperty("subject", content.getSubject());
            body.addProperty("body", content.getBody());
        }
        if (o.getChannelId() != null) body.addProperty("channel_id", o.getChannelId());
        if (o.getGroupId() != null) body.addProperty("group_id", o.getGroupId());

        // A template may be given as "group-name:template-name" to route via a channel group,
        // or as "route:name" to send through a saved fan-out route. An explicit option wins
        // over the prefix. A route request must not carry a channel_type: the route defines
        // every channel, so the type argument is ignored.
        String routeName = o.getRouteName();
        String groupName = o.getGroupName();
        String templateName = o.getTemplateName();
        if (templateName != null) {
            Ref ref = parseRef(templateName);
            if (ref.route != null) {
                if (routeName == null) routeName = ref.route;
                templateName = ref.template;
            } else if (ref.group != null) {
                if (groupName == null) groupName = ref.group;
                templateName = ref.template;
            } else {
                templateName = ref.template;
            }
        }
        if (routeName != null) {
            if (groupName != null) {
                throw new NotifierException("a route send cannot also set a channel group");
            }
            body.addProperty("route_name", routeName);
        } else {
            body.addProperty("channel_type", type.getValue());
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
        if (o.isSimulated()) body.addProperty("simulated", true);

        // At least one of body / template_id / template_name must be present. A
        // route send is the exception: the route itself selects the templates.
        boolean missingContent = !body.has("body") || isBlank(body.get("body").getAsString());
        if (routeName == null && missingContent && !body.has("template_id") && !body.has("template_name")) {
            throw new NotifierException("body, template_id, or template_name is required");
        }
        return body;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** A parsed "route:name", "group:template" or plain template reference. */
    private static final class Ref {
        final String route;
        final String group;
        final String template;

        Ref(String route, String group, String template) {
            this.route = route;
            this.group = group;
            this.template = template;
        }
    }

    /** Parses "route:name", "group-name:template-name" or a plain template name. */
    private static Ref parseRef(String template) {
        if (template != null) {
            int idx = template.indexOf(':');
            if (idx > 0 && idx < template.length() - 1) {
                String prefix = template.substring(0, idx);
                String rest = template.substring(idx + 1);
                if ("route".equals(prefix)) {
                    return new Ref(rest, null, null);
                }
                return new Ref(null, prefix, rest);
            }
        }
        return new Ref(null, null, template);
    }

    /**
     * Percent-encodes one path segment.
     *
     * Without this a value containing {@code /}, {@code ?}, {@code #}, or a newline changes
     * the shape of the request: {@code status("../../v1/channels")} would address a different
     * endpoint, and the API key goes out with whatever request results. Values reaching this
     * method are often opaque ids echoed back from an upstream provider, so they are
     * attacker-influenced in the general case.
     *
     * {@code URLEncoder} targets form bodies, where a space is {@code +}; in a path segment a
     * space must be {@code %20}, so the plus is corrected after encoding.
     */
    private static String encodePathSegment(String value, String field) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is guaranteed by the JVM; unreachable in practice.
            throw new IllegalArgumentException(field + " could not be encoded", e);
        }
    }

    /**
     * Refuses a base URL that would send the API key in cleartext.
     *
     * <p>Loopback is exempt because a local target cannot be eavesdropped from the network;
     * everything else must be https unless the caller opts out explicitly.
     */
    private static void requireSecureBaseUrl(String baseUrl, boolean allowInsecure) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl is not a valid URI: " + baseUrl, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("baseUrl must include a scheme: " + baseUrl);
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if (!"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("baseUrl must use http or https, got: " + scheme);
        }
        if (allowInsecure || isLoopbackHost(uri.getHost())) {
            return;
        }
        throw new IllegalArgumentException("baseUrl is not https (" + baseUrl + "); the API key would be "
                + "sent in cleartext. Use https, or pass allowInsecureBaseUrl=true for a trusted local target.");
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
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

    /**
     * Issues a DELETE. A successful response may legitimately carry no body
     * ({@code DELETE /v1/push/devices/{id}} answers 204), so this path tolerates an empty body
     * where {@link #execute} would report one as unexpected. Error responses are mapped exactly
     * as {@link #execute} maps them.
     */
    private JsonObject deleteJson(String path) {
        HttpDelete delete = new HttpDelete(baseUrl + path);
        delete.setHeader("Accept", "application/json");
        delete.setHeader("X-API-Key", apiKey);
        return executeAllowingEmptyBody(delete);
    }

    private JsonObject executeAllowingEmptyBody(HttpUriRequest request) {
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int code = response.getStatusLine().getStatusCode();
            // A 204 carries no entity at all, and EntityUtils refuses null, so read defensively.
            String raw = response.getEntity() == null ? null
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            JsonElement parsed = parseJsonSafely(raw);

            if (code < 200 || code >= 300) {
                throw toException(code, parsed);
            }
            if (parsed == null || parsed.isJsonNull()) {
                return null;
            }
            if (!parsed.isJsonObject()) {
                throw new NotifierException("Unexpected response from Notifier API (HTTP " + code + ")");
            }
            return parsed.getAsJsonObject();
        } catch (IOException e) {
            throw new HttpException("HTTP request failed: " + e.getMessage(), 0);
        }
    }

    private JsonObject execute(HttpUriRequest request) {
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int code = response.getStatusLine().getStatusCode();
            String raw = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            JsonElement parsed = parseJsonSafely(raw);

            if (code < 200 || code >= 300) {
                throw toException(code, parsed);
            }
            if (parsed == null || !parsed.isJsonObject()) {
                throw new NotifierException("Unexpected response from Notifier API (HTTP " + code + ")");
            }
            return parsed.getAsJsonObject();
        } catch (IOException e) {
            throw new HttpException("HTTP request failed: " + e.getMessage(), 0);
        }
    }

    /**
     * Parses a response body without ever putting its bytes into an exception message: a
     * notification response can echo rendered content, including an OTP.
     */
    private static JsonElement parseJsonSafely(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(raw, JsonObject.class);
        } catch (RuntimeException e) {
            throw new NotifierException("Notifier API returned a response that could not be parsed");
        }
    }

    /**
     * Maps a non-2xx response to the appropriate exception. If the body carries a Notifier
     * error ({@code error} field), an {@link ApiException} is thrown with message set to the
     * error text, and code set to the machine {@code code} when it is numeric, else the HTTP
     * status. Transport-level failures (no body) become an {@link HttpException}.
     */
    private NotifierException toException(int code, JsonElement parsed) {
        int businessCode = 0;
        boolean hasError = false;
        if (parsed != null && parsed.isJsonObject()) {
            JsonObject obj = parsed.getAsJsonObject();
            hasError = obj.has("error");
            if (obj.has("code")) {
                businessCode = parseErrorCode(obj.get("code"));
            }
        }
        // Never copy the server's free-text `error` into the exception: it is attacker- and
        // template-influenced and could echo rendered content. Callers branch on the type and
        // code, not on message text.
        String message = "Notifier API error (HTTP " + code
                + (businessCode != 0 ? ", code " + businessCode : "") + ")";
        if (hasError) {
            return new ApiException(message, businessCode != 0 ? businessCode : code);
        }
        return new HttpException("HTTP " + code, code);
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
