package com.softino.notifier.channel;

import com.softino.notifier.ChannelType;
import com.softino.notifier.Content;
import com.softino.notifier.NotifierApi;
import com.softino.notifier.SendOptions;
import com.softino.notifier.SendResult;

/** Typed convenience API for the Slack channel. Delegates to the generic core. */
public final class SlackChannelApi implements ChannelApi {

    private final NotifierApi core;

    public SlackChannelApi(NotifierApi core) {
        this.core = core;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.SLACK;
    }

    /** Sends a text message to a channel or user id. */
    public SendResult send(String target, String text) {
        return core.send(ChannelType.SLACK, target, Content.of(text));
    }

    /** Sends a text message with options. */
    public SendResult send(String target, String text, SendOptions opts) {
        return core.send(ChannelType.SLACK, target, Content.of(text), opts);
    }
}
