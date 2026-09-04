package com.softino.notifier;

import com.google.gson.JsonObject;
import com.softino.notifier.exception.ApiException;
import com.softino.notifier.exception.HttpException;
import com.softino.notifier.exception.NotifierException;
import com.softino.notifier.kavenegar.enums.MessageStatus;
import com.softino.notifier.kavenegar.enums.MessageType;
import com.softino.notifier.kavenegar.enums.MetaData;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the SDK model/contract layer. These do not hit the network; they verify
 * the value objects, the open ChannelType, SendResult/StatusResult JSON parsing, and the
 * Kavenegar-compat enum/exception mapping.
 */
public class SdkTest {

    @Test
    public void channelTypeIsOpenAndExtensible() {
        assertEquals("sms", ChannelType.SMS.getValue());
        assertEquals(ChannelType.TELEGRAM, ChannelType.of("telegram"));
        // Unknown / future channels must work without an SDK change:
        ChannelType future = ChannelType.of("whatsapp");
        assertEquals("whatsapp", future.getValue());
        assertTrue(ChannelType.of("whatsapp").equals(future));
        // Built-in constant for a value keeps equality with of():
        assertTrue(ChannelType.SMS.equals(ChannelType.of("sms")));
    }

    @Test
    public void sendOptionsBuilderCarriesFields() {
        SendOptions o = SendOptions.builder()
                .templateId("tpl-1")
                .templateVar("token", "123456")
                .idempotencyKey("idem-1")
                .locale("en")
                .build();
        assertEquals("tpl-1", o.getTemplateId());
        assertEquals("123456", o.getTemplateVars().get("token"));
        assertEquals("idem-1", o.getIdempotencyKey());
        assertEquals("en", o.getLocale());
        assertNull(o.getChannelId());
    }

    @Test
    public void sendResultParsesNotifierEnvelope() {
        JsonObject o = new JsonObject();
        o.addProperty("id", "c33aa8ec-e20c-4aa1-90b8-ada99822c3d0");
        o.addProperty("status", "queued");
        o.addProperty("channel_type", "sms");
        o.addProperty("recipient", "+989120000000");
        o.addProperty("body", "Your OTP is 1234");
        o.addProperty("provider", "kavenegar");
        o.addProperty("provider_message_id", "12345678");

        SendResult r = SendResult.from(o);
        assertEquals("c33aa8ec-e20c-4aa1-90b8-ada99822c3d0", r.getId());
        assertEquals("c33aa8ec-e20c-4aa1-90b8-ada99822c3d0", r.getMessageId());
        assertEquals("queued", r.getStatus());
        assertEquals("kavenegar", r.getProvider());
        assertEquals("12345678", r.getProviderMessageId());
        assertTrue(r.isDelivered() == false);
    }

    @Test
    public void statusResultIsDeliveredFlag() {
        JsonObject o = new JsonObject();
        o.addProperty("id", "abc");
        o.addProperty("status", "delivered");
        StatusResult s = StatusResult.from(o);
        assertTrue(s.isDelivered());
        assertTrue(s.isFailed() == false);
    }

    @Test
    public void kavenegarCompatibleEnums() {
        assertEquals(MessageType.MobileMemory, MessageType.valueOf(1));
        assertEquals(MessageType.Flash, MessageType.valueOf(0));
        assertEquals(MessageStatus.Delivered, MessageStatus.valueOf(10));
        assertEquals(MetaData.InvalidApiKey, MetaData.valueOf(101));
        assertEquals(1, MessageStatus.Queued.getValue());
    }

    @Test
    public void exceptionsCarryCodes() {
        ApiException api = new ApiException("bad", 101);
        assertEquals(101, api.getCode());

        HttpException http = new HttpException("boom", 500);
        assertEquals(500, http.getCode());
        assertTrue(http instanceof NotifierException);
    }

    @Test
    public void bulkMessagesCarryFields() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("token", "99");
        RecipientMessage m = RecipientMessage.builder()
                .recipient("+989120000000")
                .subjectAndBody("Hi", "Your code is 99")
                .templateVars(vars)
                .build();
        assertEquals("+989120000000", m.getRecipient());
        assertEquals("Your code is 99", m.getContent().getBody());
        assertEquals("99", m.getTemplateVars().get("token"));
    }
}
