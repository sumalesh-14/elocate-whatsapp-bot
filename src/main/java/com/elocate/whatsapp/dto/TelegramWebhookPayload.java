package com.elocate.whatsapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Minimal deserialization of Telegram's Update object.
 * Covers text messages and callback_query (inline button taps).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TelegramWebhookPayload {

    @JsonProperty("update_id")
    private Long updateId;

    private Message message;

    @JsonProperty("callback_query")
    private CallbackQuery callbackQuery;

    // ── Text message ──────────────────────────────────────────────────
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        @JsonProperty("message_id")
        private Long messageId;
        private From from;
        private Chat chat;
        private String text;
    }

    // ── Inline button tap ─────────────────────────────────────────────
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CallbackQuery {
        private String id;          // must be answered with answerCallbackQuery
        private From from;
        private Message message;    // the original message the button was on
        private String data;        // the callback_data we set on the button
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class From {
        private Long id;            // Telegram user ID — used as session key
        @JsonProperty("first_name")
        private String firstName;
        @JsonProperty("last_name")
        private String lastName;
        private String username;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Chat {
        private Long id;            // same as user ID for private chats
    }
}
