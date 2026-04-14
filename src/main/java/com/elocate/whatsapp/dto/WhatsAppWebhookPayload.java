package com.elocate.whatsapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Minimal deserialization of Meta's WhatsApp webhook payload.
 * Only the fields we actually need are mapped.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WhatsAppWebhookPayload {

    private List<Entry> entry;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        private List<Change> changes;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Change {
        private Value value;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Value {
        private List<Message> messages;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String from;   // sender's phone number
        private String type;   // "text" or "interactive"
        private TextBody text;
        private Interactive interactive;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TextBody {
        private String body;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Interactive {
        @JsonProperty("button_reply")
        private ButtonReply buttonReply;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ButtonReply {
        private String id;
        private String title;
    }
}
