package com.softino.notifier.kavenegar;

import com.softino.notifier.ChannelType;
import com.softino.notifier.NotifierApi;
import com.softino.notifier.SendOptions;
import com.softino.notifier.SendPriority;
import com.softino.notifier.exception.NotifierClosedException;
import com.softino.notifier.exception.NotifierQueueFullException;
import com.softino.notifier.kavenegar.enums.MessageStatus;
import com.softino.notifier.kavenegar.enums.MessageType;
import com.softino.notifier.kavenegar.excepctions.ApiException;
import com.softino.notifier.kavenegar.excepctions.BaseException;
import com.softino.notifier.kavenegar.excepctions.HttpException;
import com.softino.notifier.kavenegar.models.CountInboxResult;
import com.softino.notifier.kavenegar.models.CountOutboxResult;
import com.softino.notifier.kavenegar.models.ReceiveResult;
import com.softino.notifier.kavenegar.models.SendResult;
import com.softino.notifier.kavenegar.models.StatusLocalMessageIdResult;
import com.softino.notifier.kavenegar.models.StatusResult;
import com.softino.notifier.kavenegar.models.AccountConfigResult;
import com.softino.notifier.kavenegar.models.AccountInfoResult;
import com.softino.notifier.kavenegar.models.CountPostalCodeResult;
import com.softino.notifier.kavenegar.utils.PairValue;
import com.softino.notifier.kavenegar.utils.StatusMapping;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Kavenegar-java drop-in facade.
 *
 * <p>This class reproduces Kavenegar-java's public method surface and model/enum/exception
 * types under the {@code com.softino.notifier.kavenegar} package, so an existing integration
 * migrates by renaming the package prefix {@code com.kavenegar.sdk} → {@code
 * com.softino.notifier.kavenegar}. Calls are delegated to the channel-agnostic core
 * {@link NotifierApi}, which routes to whatever SMS provider is configured on the tenant.</p>
 *
 * <p>In 1.2 the OTP path gained non-blocking equivalents: {@link #verifyLookupAsync} returns a
 * future and {@link #verifyLookupAndForget} returns as soon as the send is queued (default
 * {@link SendPriority#HIGH}). The original {@code verifyLookup} overloads are unchanged.</p>
 *
 * <p>Semantic differences to be aware of (Notifier is a UUID-keyed, async, multi-provider hub):</p>
 * <ul>
 *   <li>{@code messageId} in {@link SendResult}/{@link StatusResult} is derived from the
 *       provider message id when available, else {@code 0}, because Notifier does not return
 *       a numeric id synchronously on send.</li>
 *   <li>{@code status} values are mapped onto the Kavenegar {@link MessageStatus} enum from
 *       Notifier's coarse status string; Kavenegar-specific numeric codes (e.g. literal
 *       {@code 5}) do not correspond to Notifier statuses.</li>
 *   <li>Kavenegar features Notifier does not expose throw a Kavenegar-typed
 *       {@link BaseException} describing the gap rather than silently misbehaving.</li>
 * </ul>
 */
public class KavenegarApi implements AutoCloseable {

    private final NotifierApi core;

    /** Constructs a facade pointing at the default Notifier base URL. */
    public KavenegarApi(String apiKey) {
        this.core = new NotifierApi(apiKey);
    }

    /** Constructs a facade with a custom base URL. */
    public KavenegarApi(String apiKey, String baseUrl) {
        this.core = new NotifierApi(apiKey, baseUrl);
    }

    /**
     * Constructs a facade around an already-configured {@link NotifierApi}, so queue sizes,
     * deadlines, retry policy and the send listener are shared with the rest of the service.
     * Closing the facade closes the supplied core client.
     */
    public KavenegarApi(NotifierApi core) {
        this.core = core;
    }

    // ------------------------------------------------------------------
    // Generic send
    // ------------------------------------------------------------------

    public SendResult send(String sender, String receptor, String message) {
        return execute(() -> toSend(core.send(ChannelType.SMS, receptor, message)));
    }

    public SendResult send(String sender, String receptor, String message, MessageType type, long date) {
        return execute(() -> toSend(core.send(ChannelType.SMS, receptor, message)));
    }

    public SendResult send(String sender, String receptor, String message, MessageType type, long date, String localId) {
        return execute(() -> toSend(core.send(ChannelType.SMS, receptor, message)));
    }

    public SendResult send(String sender, String receptor, String message, String localId) {
        return execute(() -> toSend(core.send(ChannelType.SMS, receptor, message)));
    }

    public List<SendResult> send(String sender, List<String> receptors, String message) {
        return sendReceptors(sender, receptors, message);
    }

    public List<SendResult> send(String sender, List<String> receptors, String message, MessageType type, long date) {
        return sendReceptors(sender, receptors, message);
    }

    public List<SendResult> send(String sender, List<String> receptors, String message, MessageType type, long date,
                                 List<String> localIds) {
        return sendReceptors(sender, receptors, message);
    }

    private List<SendResult> sendReceptors(String sender, List<String> receptors, String message) {
        return execute(() -> {
            List<SendResult> out = new ArrayList<>();
            if (receptors != null) {
                for (String r : receptors) {
                    out.add(toSend(core.send(ChannelType.SMS, r, message)));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------
    // sendArray
    // ------------------------------------------------------------------

    public List<SendResult> sendArray(List<String> senders, List<String> receptors,
                                      List<String> messages, List<MessageType> types,
                                      long date, List<String> localIds) {
        return sendArrayInternal(firstOrNull(senders), receptors, messages);
    }

    public List<SendResult> sendArray(List<String> senders, List<String> receptors, List<String> messages) {
        return sendArrayInternal(firstOrNull(senders), receptors, messages);
    }

    public List<SendResult> sendArray(List<String> senders, List<String> receptors,
                                      List<String> messages, String localId) {
        return sendArrayInternal(firstOrNull(senders), receptors, messages);
    }

    public List<SendResult> sendArray(List<String> senders, List<String> receptors,
                                      List<String> messages, List<MessageType> types, long date, String localId) {
        return sendArrayInternal(firstOrNull(senders), receptors, messages);
    }

    public List<SendResult> sendArray(String sender, List<String> receptors, List<String> messages) {
        return sendArrayInternal(sender, receptors, messages);
    }

    public List<SendResult> sendArray(String sender, List<String> receptors,
                                      List<String> messages, String localId) {
        return sendArrayInternal(sender, receptors, messages);
    }

    public List<SendResult> sendArray(String sender, List<String> receptors,
                                      List<String> messages, List<MessageType> types, long date, String localId) {
        return sendArrayInternal(sender, receptors, messages);
    }

    private List<SendResult> sendArrayInternal(String sender, List<String> receptors, List<String> messages) {
        return execute(() -> {
            List<SendResult> out = new ArrayList<>();
            if (receptors != null) {
                for (int i = 0; i < receptors.size(); i++) {
                    String body = (messages != null && !messages.isEmpty())
                            ? messages.get(Math.min(i, messages.size() - 1)) : "";
                    out.add(toSend(core.send(ChannelType.SMS, receptors.get(i), body)));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    public StatusResult status(long messageId) {
        return execute(() -> toStatus(core.statusByProviderMessageId(String.valueOf(messageId))));
    }

    public List<StatusResult> status(List<Long> messageIds) {
        return execute(() -> {
            List<StatusResult> out = new ArrayList<>();
            if (messageIds != null) {
                for (Long id : messageIds) {
                    out.add(toStatus(core.statusByProviderMessageId(String.valueOf(id))));
                }
            }
            return out;
        });
    }

    /**
     * Fetches the delivery status of a notification by its Notifier UUID, not by the provider
     * message id.
     *
     * <p>Notifier is async: {@code send}/{@code verifyLookup} return before the provider has
     * been reached, so {@link SendResult#getMessageId()} is {@code 0} at send time and
     * {@link #status(long)} (by provider message id) returns {@code not found} for a freshly
     * sent message. The notifier UUID is always returned synchronously (see
     * {@link SendResult#getNotificationId()}), so this method always resolves and is the
     * reliable way to poll a message you just sent.</p>
     *
     * <pre>{@code
     * SendResult sent = api.verifyLookup(phone, otp, "vibe:salerequest");
     * String uuid = sent.getNotificationId();      // reliable, always present
     * StatusResult st = api.statusByNotificationId(uuid);
     * }</pre>
     */
    public StatusResult statusByNotificationId(String notificationId) {
        return execute(() -> toStatus(core.status(notificationId)));
    }

    public StatusLocalMessageIdResult statusLocalMessageId(long localId) {
        return execute(() -> toStatusLocal(core.statusByProviderMessageId(String.valueOf(localId)), localId));
    }

    public List<StatusLocalMessageIdResult> statusLocalMessageId(List<Long> localIds) {
        return execute(() -> {
            List<StatusLocalMessageIdResult> out = new ArrayList<>();
            if (localIds != null) {
                for (Long id : localIds) {
                    out.add(toStatusLocal(core.statusByProviderMessageId(String.valueOf(id)), id));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------
    // Verify lookup (OTP / template)
    // ------------------------------------------------------------------

    public SendResult verifyLookup(String receptor, String token, String token2, String token3, String template) {
        // The cast keeps the call unambiguous: without it, `null` also matches the
        // List<PairValue> overload below.
        return verifyLookup(receptor, token, token2, token3, template, (Boolean) null);
    }

    /**
     * Sends a template lookup, optionally rehearsing it.
     *
     * <p>{@code simulated} may be null, which means "send for real" and is the behaviour of the
     * overload without the parameter. When true the whole pipeline runs — routing, group
     * selection, template rendering and the panel — but the provider is never contacted, so an
     * existing Kavenegar call site can be put into rehearsal by adding one argument.</p>
     */
    public SendResult verifyLookup(String receptor, String token, String token2, String token3, String template,
                                   Boolean simulated) {
        // Positional: values are sent as an ordered template_params array and bound to the
        // template's declared param_names (mirrors Kavenegar's %token%, %token2%, %token3%).
        // The template may be "group:template" to route via a channel group, or
        // "route:name" to send through a saved fan-out route. The facade signature is
        // unchanged; the prefix only changes how the backend resolves the send.
        final Ref ref = parseRef(template);
        return execute(() -> {
            Object[] params = nonEmptyTokens(token, token2, token3);
            return toSend(core.sendTemplateByName(ChannelType.SMS, receptor, ref.template,
                    options(ref, simulated), params));
        });
    }

    public SendResult verifyLookup(String receptor, String token, String token2, String token3,
                                   String template, List<PairValue> params) {
        return verifyLookup(receptor, token, token2, token3, template, params, null);
    }

    /** Sends a template lookup with named parameters, optionally rehearsing it. */
    public SendResult verifyLookup(String receptor, String token, String token2, String token3,
                                   String template, List<PairValue> params, Boolean simulated) {
        final Ref ref = parseRef(template);
        return execute(() -> {
            Map<String, Object> vars = new java.util.LinkedHashMap<>(tokens(token, token2, token3));
            if (params != null) {
                for (PairValue p : params) {
                    if (p.getKey() != null && p.getValue() != null) {
                        vars.put(p.getKey(), p.getValue());
                    }
                }
            }
            return toSend(core.sendTemplateByName(ChannelType.SMS, receptor, ref.template, vars,
                    options(ref, simulated)));
        });
    }

    public SendResult verifyLookup(String receptor, String token, String template) {
        return verifyLookup(receptor, token, "", "", template);
    }

    /**
     * The two-value form of {@link #verifyLookup(String, String, String, String, String, Boolean)},
     * optionally rehearsed.
     */
    public SendResult verifyLookup(String receptor, String token, String template, Boolean simulated) {
        return verifyLookup(receptor, token, "", "", template, simulated);
    }

    // ------------------------------------------------------------------
    // Non-blocking verifyLookup / send
    // ------------------------------------------------------------------

    /**
     * Non-blocking {@link #verifyLookup(String, String, String)}. Returns as soon as the send is
     * queued; the future completes after the hub accepts the notification, on an SDK thread.
     * Delivery to the handset still happens asynchronously on the hub side.
     *
     * <p>The send defaults to {@link SendPriority#HIGH} (OTP), so it never queues behind bulk
     * traffic. Pass {@link SendOptions} to override the deadline, idempotency key or trace id.</p>
     */
    public CompletableFuture<SendResult> verifyLookupAsync(String receptor, String token, String template) {
        return verifyLookupAsync(receptor, token, template, SendOptions.none());
    }

    /** Non-blocking verifyLookup with options. */
    public CompletableFuture<SendResult> verifyLookupAsync(String receptor, String token, String template,
                                                           SendOptions opts) {
        return verifyLookupAsync(receptor, token, "", "", template, (Boolean) null, opts);
    }

    /** Non-blocking verifyLookup with up to three template tokens. */
    public CompletableFuture<SendResult> verifyLookupAsync(String receptor, String token, String token2,
                                                           String token3, String template) {
        return verifyLookupAsync(receptor, token, token2, token3, template, (Boolean) null, SendOptions.none());
    }

    /** Non-blocking verifyLookup with up to three tokens and options. */
    public CompletableFuture<SendResult> verifyLookupAsync(String receptor, String token, String token2,
                                                           String token3, String template, SendOptions opts) {
        return verifyLookupAsync(receptor, token, token2, token3, template, (Boolean) null, opts);
    }

    /** Non-blocking verifyLookup, optionally rehearsed (same meaning as the blocking overload). */
    public CompletableFuture<SendResult> verifyLookupAsync(String receptor, String token, String template,
                                                           Boolean simulated) {
        return verifyLookupAsync(receptor, token, "", "", template, simulated, SendOptions.none());
    }

    /** Non-blocking verifyLookup with named template parameters. */
    public CompletableFuture<SendResult> verifyLookupAsync(String receptor, String token, String token2,
                                                           String token3, String template,
                                                           List<PairValue> params) {
        return verifyLookupAsync(receptor, token, token2, token3, template, params, (Boolean) null, SendOptions.none());
    }

    /** Non-blocking verifyLookup with named parameters and options. */
    public CompletableFuture<SendResult> verifyLookupAsync(String receptor, String token, String token2,
                                                           String token3, String template,
                                                           List<PairValue> params, SendOptions opts) {
        return verifyLookupAsync(receptor, token, token2, token3, template, params, (Boolean) null, opts);
    }

    /** Non-blocking verifyLookup with named parameters, rehearsal flag and options. */
    public CompletableFuture<SendResult> verifyLookupAsync(String receptor, String token, String token2,
                                                           String token3, String template,
                                                           List<PairValue> params, Boolean simulated,
                                                           SendOptions opts) {
        final Ref ref = parseRef(template);
        Map<String, Object> vars = namedVars(token, token2, token3, params);
        SendOptions effective = highPriority(lookupOptions(ref, simulated, opts));
        CompletableFuture<com.softino.notifier.SendResult> coreFuture =
                core.sendTemplateByNameAsync(ChannelType.SMS, receptor, ref.template, vars, effective);
        return coreFuture.thenApply(new java.util.function.Function<com.softino.notifier.SendResult, SendResult>() {
            @Override
            public SendResult apply(com.softino.notifier.SendResult result) {
                return toSend(result);
            }
        });
    }

    private CompletableFuture<SendResult> verifyLookupAsync(String receptor, String token, String token2,
                                                            String token3, String template, Boolean simulated,
                                                            SendOptions opts) {
        final Ref ref = parseRef(template);
        SendOptions effective = highPriority(lookupOptions(ref, simulated, opts));
        CompletableFuture<com.softino.notifier.SendResult> coreFuture =
                core.sendTemplateByNameAsync(ChannelType.SMS, receptor, ref.template,
                        effective, nonEmptyTokens(token, token2, token3));
        try {
            return coreFuture.thenApply(new java.util.function.Function<com.softino.notifier.SendResult, SendResult>() {
                @Override
                public SendResult apply(com.softino.notifier.SendResult result) {
                    return toSend(result);
                }
            });
        } catch (RuntimeException e) {
            CompletableFuture<SendResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /**
     * Fire-and-forget verifyLookup: returns after the send is queued and performs no network
     * I/O on the calling thread. The result is not observable; failures are counted in
     * {@link NotifierApi#stats()} ({@code dropped}) and reported to the configured
     * {@code SendListener}.
     *
     * @throws NotifierQueueFullException if the HIGH lane is full (nothing was sent)
     * @throws NotifierClosedException    if the client is closed
     */
    public void verifyLookupAndForget(String receptor, String token, String template)
            throws NotifierQueueFullException, NotifierClosedException {
        verifyLookupAndForget(receptor, token, "", "", template, (Boolean) null, SendOptions.none());
    }

    /** Fire-and-forget verifyLookup with options (deadline, traceId, idempotencyKey, ...). */
    public void verifyLookupAndForget(String receptor, String token, String template, SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        verifyLookupAndForget(receptor, token, "", "", template, (Boolean) null, opts);
    }

    /** Fire-and-forget verifyLookup with up to three template tokens. */
    public void verifyLookupAndForget(String receptor, String token, String token2, String token3,
                                      String template)
            throws NotifierQueueFullException, NotifierClosedException {
        verifyLookupAndForget(receptor, token, token2, token3, template, (Boolean) null, SendOptions.none());
    }

    /** Fire-and-forget verifyLookup with up to three tokens and options. */
    public void verifyLookupAndForget(String receptor, String token, String token2, String token3,
                                      String template, SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        verifyLookupAndForget(receptor, token, token2, token3, template, (Boolean) null, opts);
    }

    /** Fire-and-forget verifyLookup, optionally rehearsed. */
    public void verifyLookupAndForget(String receptor, String token, String template, Boolean simulated)
            throws NotifierQueueFullException, NotifierClosedException {
        verifyLookupAndForget(receptor, token, "", "", template, simulated, SendOptions.none());
    }

    /** Fire-and-forget verifyLookup with named template parameters. */
    public void verifyLookupAndForget(String receptor, String token, String token2, String token3,
                                      String template, List<PairValue> params)
            throws NotifierQueueFullException, NotifierClosedException {
        verifyLookupAndForget(receptor, token, token2, token3, template, params, (Boolean) null, SendOptions.none());
    }

    /** Fire-and-forget verifyLookup with named parameters and options. */
    public void verifyLookupAndForget(String receptor, String token, String token2, String token3,
                                      String template, List<PairValue> params, SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        verifyLookupAndForget(receptor, token, token2, token3, template, params, (Boolean) null, opts);
    }

    /** Fire-and-forget verifyLookup with named parameters, rehearsal flag and options. */
    public void verifyLookupAndForget(String receptor, String token, String token2, String token3,
                                      String template, List<PairValue> params, Boolean simulated,
                                      SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        final Ref ref = parseRef(template);
        Map<String, Object> vars = namedVars(token, token2, token3, params);
        SendOptions effective = highPriority(lookupOptions(ref, simulated, opts));
        core.sendTemplateByNameAndForget(ChannelType.SMS, receptor, ref.template, vars, effective);
    }

    private void verifyLookupAndForget(String receptor, String token, String token2, String token3,
                                       String template, Boolean simulated, SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        final Ref ref = parseRef(template);
        SendOptions effective = highPriority(lookupOptions(ref, simulated, opts));
        core.sendTemplateByNameAndForget(ChannelType.SMS, receptor, ref.template,
                effective, nonEmptyTokens(token, token2, token3));
    }

    /** Non-blocking {@link #send(String, String, String)}. */
    public CompletableFuture<SendResult> sendAsync(String sender, String receptor, String message) {
        return sendAsync(sender, receptor, message, SendOptions.none());
    }

    /** Non-blocking plain send with options. */
    public CompletableFuture<SendResult> sendAsync(String sender, String receptor, String message,
                                                   SendOptions opts) {
        SendOptions o = opts == null ? SendOptions.none() : opts;
        return core.sendAsync(ChannelType.SMS, receptor, message,
                o.getPriority() == null ? o.toBuilder().priority(SendPriority.BULK).build() : o)
                .thenApply(new java.util.function.Function<com.softino.notifier.SendResult, SendResult>() {
                    @Override
                    public SendResult apply(com.softino.notifier.SendResult result) {
                        return toSend(result);
                    }
                });
    }

    /** Fire-and-forget plain send. */
    public void sendAndForget(String sender, String receptor, String message)
            throws NotifierQueueFullException, NotifierClosedException {
        sendAndForget(sender, receptor, message, SendOptions.none());
    }

    /** Fire-and-forget plain send with options. */
    public void sendAndForget(String sender, String receptor, String message, SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        SendOptions o = opts == null ? SendOptions.none() : opts;
        core.sendAndForget(ChannelType.SMS, receptor, message,
                o.getPriority() == null ? o.toBuilder().priority(SendPriority.BULK).build() : o);
    }

    /**
     * Non-blocking form of the list-recipient {@code send} overloads: one queued send per
     * recipient. The returned future completes when every send has settled; a failure in any
     * one makes it fail (some sends may already have been accepted and delivered).
     */
    public CompletableFuture<List<SendResult>> sendAsync(String sender, List<String> receptors,
                                                         String message) {
        return sendAsync(sender, receptors, message, SendOptions.none());
    }

    /** Non-blocking list-recipient send with options. */
    public CompletableFuture<List<SendResult>> sendAsync(String sender, List<String> receptors,
                                                         String message, SendOptions opts) {
        List<CompletableFuture<SendResult>> parts = new java.util.ArrayList<>();
        if (receptors != null) {
            for (String receptor : receptors) {
                parts.add(sendAsync(sender, receptor, message, opts));
            }
        }
        return combine(parts);
    }

    /**
     * Fire-and-forget list-recipient send. If the queue fills part-way through, the already
     * queued recipients stay queued and {@link NotifierQueueFullException} is thrown for the
     * one that was refused; there is no rollback.
     */
    public void sendAndForget(String sender, List<String> receptors, String message)
            throws NotifierQueueFullException, NotifierClosedException {
        sendAndForget(sender, receptors, message, SendOptions.none());
    }

    /** Fire-and-forget list-recipient send with options. */
    public void sendAndForget(String sender, List<String> receptors, String message, SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        if (receptors != null) {
            for (String receptor : receptors) {
                sendAndForget(sender, receptor, message, opts);
            }
        }
    }

    /** Non-blocking form of {@code sendArray}; one queued send per recipient. */
    public CompletableFuture<List<SendResult>> sendArrayAsync(String sender, List<String> receptors,
                                                              List<String> messages) {
        return sendArrayAsync(sender, receptors, messages, SendOptions.none());
    }

    /** Non-blocking form of {@code sendArray} with options. */
    public CompletableFuture<List<SendResult>> sendArrayAsync(String sender, List<String> receptors,
                                                              List<String> messages, SendOptions opts) {
        List<CompletableFuture<SendResult>> parts = new java.util.ArrayList<>();
        if (receptors != null) {
            for (int i = 0; i < receptors.size(); i++) {
                String body = (messages != null && !messages.isEmpty())
                        ? messages.get(Math.min(i, messages.size() - 1)) : "";
                parts.add(sendAsync(sender, receptors.get(i), body, opts));
            }
        }
        return combine(parts);
    }

    /** Non-blocking form of {@code sendArray} where each entry names its own first sender. */
    public CompletableFuture<List<SendResult>> sendArrayAsync(List<String> senders, List<String> receptors,
                                                              List<String> messages) {
        return sendArrayAsync(firstOrNull(senders), receptors, messages);
    }

    /** Fire-and-forget form of {@code sendArray}; partial queueing is not rolled back. */
    public void sendArrayAndForget(String sender, List<String> receptors, List<String> messages)
            throws NotifierQueueFullException, NotifierClosedException {
        sendArrayAndForget(sender, receptors, messages, SendOptions.none());
    }

    /** Fire-and-forget form of {@code sendArray} with options. */
    public void sendArrayAndForget(String sender, List<String> receptors, List<String> messages,
                                   SendOptions opts)
            throws NotifierQueueFullException, NotifierClosedException {
        if (receptors != null) {
            for (int i = 0; i < receptors.size(); i++) {
                String body = (messages != null && !messages.isEmpty())
                        ? messages.get(Math.min(i, messages.size() - 1)) : "";
                sendAndForget(sender, receptors.get(i), body, opts);
            }
        }
    }

    /** Fire-and-forget form of {@code sendArray} for a list of senders. */
    public void sendArrayAndForget(List<String> senders, List<String> receptors, List<String> messages)
            throws NotifierQueueFullException, NotifierClosedException {
        sendArrayAndForget(firstOrNull(senders), receptors, messages);
    }

    private static <T> CompletableFuture<List<T>> combine(List<CompletableFuture<T>> parts) {
        return CompletableFuture.allOf(parts.toArray(new CompletableFuture<?>[0]))
                .thenApply(new java.util.function.Function<Void, List<T>>() {
                    @Override
                    public List<T> apply(Void ignored) {
                        List<T> out = new java.util.ArrayList<T>(parts.size());
                        for (CompletableFuture<T> part : parts) {
                            out.add(part.join());
                        }
                        return out;
                    }
                });
    }

    /** Non-blocking status lookup by Notifier UUID (the reliable poll after an async send). */
    public CompletableFuture<StatusResult> statusByNotificationIdAsync(String notificationId) {
        return core.statusByNotificationIdAsync(notificationId).thenApply(new java.util.function.Function<com.softino.notifier.StatusResult, StatusResult>() {
            @Override
            public StatusResult apply(com.softino.notifier.StatusResult result) {
                return toStatus(result);
            }
        });
    }

    /** Counters and gauges for the underlying core client's async path. */
    public com.softino.notifier.NotifierStats stats() {
        return core.stats();
    }

    /**
     * Waits until every accepted async send has settled, or the timeout expires. For tests,
     * demos and orderly shutdown; it parks the calling thread deliberately and is not part of
     * the send path.
     */
    public boolean awaitIdle(Duration timeout) {
        return core.awaitIdle(timeout);
    }

    /** Releases the underlying core client (drains accepted sends, then closes). */
    @Override
    public void close() {
        core.close();
    }

    private static SendOptions lookupOptions(Ref ref, Boolean simulated, SendOptions opts) {
        SendOptions.Builder b = opts == null ? SendOptions.builder() : opts.toBuilder();
        if (ref.route != null) {
            b.routeName(ref.route);
        } else if (ref.group != null) {
            b.groupName(ref.group);
        }
        if (simulated != null && simulated) {
            b.simulated(true);
        }
        return b.build();
    }

    private static SendOptions highPriority(SendOptions options) {
        return options.getPriority() == null
                ? options.toBuilder().priority(SendPriority.HIGH).build()
                : options;
    }

    /**
     * Builds the send options for a lookup: the group shorthand (when present) and whether the
     * send is a rehearsal. Kept in one place so both overloads cannot drift apart.
     */
    private static SendOptions options(Ref ref, Boolean simulated) {
        SendOptions.Builder b = SendOptions.builder();
        if (ref.route != null) {
            b.routeName(ref.route);
        } else if (ref.group != null) {
            b.groupName(ref.group);
        }
        if (simulated != null && simulated) {
            b.simulated(true);
        }
        return b.build();
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

    // "route:name" → {route, null, null}; "group:template" → {null, group, template};
    // no (or malformed) ':' → {null, null, template}.
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

    private static Map<String, Object> namedVars(String token, String token2, String token3,
                                                 List<PairValue> params) {
        Map<String, Object> vars = new java.util.LinkedHashMap<>(tokens(token, token2, token3));
        if (params != null) {
            for (PairValue p : params) {
                if (p.getKey() != null && p.getValue() != null) {
                    vars.put(p.getKey(), p.getValue());
                }
            }
        }
        return vars;
    }

    private static Map<String, Object> tokens(String token, String token2, String token3) {
        Map<String, Object> vars = new java.util.LinkedHashMap<>();
        if (token != null && !token.isEmpty()) vars.put("token", token);
        if (token2 != null && !token2.isEmpty()) vars.put("token2", token2);
        if (token3 != null && !token3.isEmpty()) vars.put("token3", token3);
        return vars;
    }

    /** Non-empty token values, in order, for positional template binding. */
    private static Object[] nonEmptyTokens(String token, String token2, String token3) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (token != null && !token.isEmpty()) out.add(token);
        if (token2 != null && !token2.isEmpty()) out.add(token2);
        if (token3 != null && !token3.isEmpty()) out.add(token3);
        return out.toArray();
    }

    // ------------------------------------------------------------------
    // Features Notifier does not yet expose — present for source parity, but throw a
    // Kavenegar-typed BaseException describing the gap (fail loudly, not silently).
    // ------------------------------------------------------------------

    public List<SendResult> select(long messageId) {
        throw unsupported("select");
    }

    public List<SendResult> select(List<Long> messageIds) {
        throw unsupported("select");
    }

    public List<SendResult> selectOutbox(long startDate, long endDate, String sender) {
        throw unsupported("selectOutbox");
    }

    public List<SendResult> latestOutbox(Long pageSize, String sender) {
        throw unsupported("latestOutbox");
    }

    public CountOutboxResult countOutbox(long startDate, long endDate, int status) {
        throw unsupported("countOutbox");
    }

    public List<StatusResult> cancel(List<Long> messageIds) {
        throw unsupported("cancel");
    }

    public List<ReceiveResult> receive(String lineNumber, int isRead) {
        throw unsupported("receive (inbound SMS)");
    }

    public CountInboxResult countInbox(long startDate, long endDate, String lineNumber, int isRead) {
        throw unsupported("countInbox (inbound SMS)");
    }

    public List<SendResult> sendByPostalCode(long postalCode, String sender, String message,
                                             long mciStartIndex, long mciCount,
                                             long mtnStartIndex, long mtnCount, long date) {
        throw unsupported("sendByPostalCode");
    }

    public List<CountPostalCodeResult> countPostalCode(Long postalCode) {
        throw unsupported("countPostalCode");
    }

    public AccountInfoResult accountInfo() {
        throw unsupported("accountInfo");
    }

    public AccountConfigResult accountConfig(
            String apiLogs, String dailyReport, String debugMode, String defaultSender, int minCreditAlarm,
            String resendFailed) {
        throw unsupported("accountConfig");
    }

    // callMakeTTS overloads (voice) — present for source parity, but Notifier has no voice channel.
    public SendResult callMakeTTS(String message, String receptor) {
        throw unsupported("callMakeTTS (voice)");
    }

    public List<SendResult> callMakeTTS(String message, List<String> receptor) {
        throw unsupported("callMakeTTS (voice)");
    }

    public List<SendResult> callMakeTTS(String message, String receptor, Long date) {
        throw unsupported("callMakeTTS (voice)");
    }

    public List<SendResult> callMakeTTS(String message, List<String> receptor, Long date) {
        throw unsupported("callMakeTTS (voice)");
    }

    public List<SendResult> callMakeTTS(String message, List<String> receptor, List<String> localId) {
        throw unsupported("callMakeTTS (voice)");
    }

    public List<SendResult> callMakeTTS(String message, List<String> receptor, String localId) {
        throw unsupported("callMakeTTS (voice)");
    }

    public List<SendResult> callMakeTTS(String message, List<String> receptors, Long date, List<String> localIds) {
        throw unsupported("callMakeTTS (voice)");
    }

    // ------------------------------------------------------------------
    // Mapping helpers
    // ------------------------------------------------------------------

    private static SendResult toSend(com.softino.notifier.SendResult r) {
        return new SendResult(
                asLong(r.getProviderMessageId()),
                StatusMapping.toStatusInt(r.getStatus()),
                StatusMapping.toStatusText(r.getStatus()),
                r.getBody(),
                "",
                r.getRecipient(),
                toEpochMillis(r.getCreatedAt()),
                0,
                r.getId());
    }

    private static StatusResult toStatus(com.softino.notifier.StatusResult r) {
        return new StatusResult(
                (int) asLong(r.getProviderMessageId()),
                StatusMapping.toKavenegarStatus(r.getStatus()),
                StatusMapping.toStatusText(r.getStatus()),
                r.getId());
    }

    private static StatusLocalMessageIdResult toStatusLocal(com.softino.notifier.StatusResult r, long localId) {
        return new StatusLocalMessageIdResult(
                (int) asLong(r.getProviderMessageId()),
                StatusMapping.toKavenegarStatus(r.getStatus()),
                StatusMapping.toStatusText(r.getStatus()),
                localId);
    }

    private static long asLong(String s) {
        if (s == null || s.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long toEpochMillis(String rfc3339) {
        if (rfc3339 == null) return 0L;
        try {
            return OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String firstOrNull(List<String> list) {
        return (list == null || list.isEmpty()) ? "" : list.get(0);
    }

    private static BaseException unsupported(String feature) {
        return new BaseException(feature + " is not supported by the Notifier platform yet.");
    }

    // ------------------------------------------------------------------
    // Exception translation: Notifier core exceptions → Kavenegar-compatible types.
    // ------------------------------------------------------------------

    private <T> T execute(Callable<T> action) {
        try {
            return action.call();
        } catch (com.softino.notifier.exception.HttpException e) {
            throw new HttpException(e.getMessage(), e.getCode());
        } catch (com.softino.notifier.exception.ApiException e) {
            throw new ApiException(e.getMessage(), e.getCode());
        } catch (com.softino.notifier.exception.NotifierException e) {
            throw new BaseException(e.getMessage());
        } catch (java.lang.Exception e) {
            throw new BaseException(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
