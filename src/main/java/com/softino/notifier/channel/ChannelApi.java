package com.softino.notifier.channel;

import com.softino.notifier.ChannelType;

/**
 * Marker for a channel-specific convenience API.
 *
 * <p>Each channel exposes a typed {@code send(...)} that internally delegates to the
 * channel-agnostic core ({@link com.softino.notifier.NotifierApi}). These are
 * <b>optional</b>: any channel can also be sent through the generic core
 * ({@code notifierApi.send(ChannelType.of("telegram"), recipient, content)}), so adding a
 * new channel never breaks existing callers — the generic core is the fallback.</p>
 */
public interface ChannelApi {

    /** The channel type this API targets. */
    ChannelType channelType();
}
