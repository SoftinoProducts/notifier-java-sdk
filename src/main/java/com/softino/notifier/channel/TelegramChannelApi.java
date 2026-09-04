package com.softino.notifier.channel;

import com.softino.notifier.ChannelType;
import com.softino.notifier.Content;
import com.softino.notifier.NotifierApi;
import com.softino.notifier.SendOptions;
import com.softino.notifier.SendResult;

/** Typed convenience API for the Telegram channel. Delegates to the generic core. */
public final class TelegramChannelApi implements ChannelApi {

    private final NotifierApi core;

    public TelegramChannelApi(NotifierApi core) {
        this.core = core;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.TELEGRAM;
    }

    /** Sends a text message to a chat id. */
    public SendResult send(String chatId, String text) {
        return core.send(ChannelType.TELEGRAM, chatId, Content.of(text));
    }

    /** Sends a text message with options. */
    public SendResult send(String chatId, String text, SendOptions opts) {
        return core.send(ChannelType.TELEGRAM, chatId, Content.of(text), opts);
    }
}
