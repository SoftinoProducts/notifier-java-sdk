package com.softino.notifier.channel;

import com.softino.notifier.ChannelType;
import com.softino.notifier.Content;
import com.softino.notifier.NotifierApi;
import com.softino.notifier.SendOptions;
import com.softino.notifier.SendResult;

/** Typed convenience API for the email channel. Delegates to the generic core. */
public final class EmailChannelApi implements ChannelApi {

    private final NotifierApi core;

    public EmailChannelApi(NotifierApi core) {
        this.core = core;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.EMAIL;
    }

    /** Sends a plain email to a recipient. */
    public SendResult send(String to, String subject, String body) {
        return core.send(ChannelType.EMAIL, to, new Content(subject, body));
    }

    /** Sends an email with options (e.g. template, callback, idempotency key). */
    public SendResult send(String to, String subject, String body, SendOptions opts) {
        return core.send(ChannelType.EMAIL, to, new Content(subject, body), opts);
    }
}
