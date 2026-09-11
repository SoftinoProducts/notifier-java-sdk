package com.softino.notifier.kavenegar;

import com.softino.notifier.ChannelType;
import com.softino.notifier.NotifierApi;
import com.softino.notifier.SendOptions;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Kavenegar-java drop-in facade.
 *
 * <p>This class reproduces Kavenegar-java's public method surface and model/enum/exception
 * types under the {@code com.softino.notifier.kavenegar} package, so an existing integration
 * migrates by renaming the package prefix {@code com.kavenegar.sdk} → {@code
 * com.softino.notifier.kavenegar}. Calls are delegated to the channel-agnostic core
 * {@link NotifierApi}, which routes to whatever SMS provider is configured on the tenant.</p>
 *
 * <p>Semantic differences to be aware of (Notifier is a UUID-keyed, async, multi-provider hub):
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
 * </p>
 */
public class KavenegarApi {

    private final NotifierApi core;

    /** Constructs a facade pointing at the default Notifier base URL. */
    public KavenegarApi(String apiKey) {
        this.core = new NotifierApi(apiKey);
    }

    /** Constructs a facade with a custom base URL. */
    public KavenegarApi(String apiKey, String baseUrl) {
        this.core = new NotifierApi(apiKey, baseUrl);
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
        // The template may be "group:template" to also route via a channel group by name.
        final String[] gt = splitGroup(template);
        return execute(() -> {
            Object[] params = nonEmptyTokens(token, token2, token3);
            return toSend(core.sendTemplateByName(ChannelType.SMS, receptor, gt[1],
                    options(gt[0], simulated), params));
        });
    }

    public SendResult verifyLookup(String receptor, String token, String token2, String token3,
                                   String template, List<PairValue> params) {
        return verifyLookup(receptor, token, token2, token3, template, params, null);
    }

    /** Sends a template lookup with named parameters, optionally rehearsing it. */
    public SendResult verifyLookup(String receptor, String token, String token2, String token3,
                                   String template, List<PairValue> params, Boolean simulated) {
        final String[] gt = splitGroup(template);
        return execute(() -> {
            Map<String, Object> vars = new java.util.LinkedHashMap<>(tokens(token, token2, token3));
            if (params != null) {
                for (PairValue p : params) {
                    if (p.getKey() != null && p.getValue() != null) {
                        vars.put(p.getKey(), p.getValue());
                    }
                }
            }
            return toSend(core.sendTemplateByName(ChannelType.SMS, receptor, gt[1], vars,
                    options(gt[0], simulated)));
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

    /**
     * Builds the send options for a lookup: the group shorthand (when present) and whether the
     * send is a rehearsal. Kept in one place so both overloads cannot drift apart.
     */
    private static SendOptions options(String groupName, Boolean simulated) {
        SendOptions.Builder b = SendOptions.builder();
        if (groupName != null) {
            b.groupName(groupName);
        }
        if (simulated != null && simulated) {
            b.simulated(true);
        }
        return b.build();
    }

    // "group:template" → {group, template}; no (or malformed) ':' → {null, template}.
    private static String[] splitGroup(String template) {
        if (template != null) {
            int idx = template.indexOf(':');
            if (idx > 0 && idx < template.length() - 1) {
                return new String[]{template.substring(0, idx), template.substring(idx + 1)};
            }
        }
        return new String[]{null, template};
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
