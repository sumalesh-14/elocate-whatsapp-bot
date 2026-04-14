package com.elocate.whatsapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Telegram Bot API.
 * Sends text messages and inline keyboard (button) messages.
 *
 * API base: https://api.telegram.org/bot{TOKEN}/
 */
@Service
@Slf4j
public class TelegramApiClient {

    private final WebClient webClient;
    private final boolean mockMode;

    public TelegramApiClient(
            @Value("${telegram.bot.token:mock}") String token,
            @Value("${telegram.api-url:https://api.telegram.org}") String apiUrl) {

        this.mockMode = "mock".equals(token);
        this.webClient = WebClient.builder()
                .baseUrl(apiUrl + "/bot" + token)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        if (mockMode) {
            log.warn("⚠️  Telegram API running in MOCK MODE — messages will be logged only.");
        }
    }

    /** Send a plain text message using HTML parse mode. */
    public void sendText(Long chatId, String text) {
        Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "text", text,
                "parse_mode", "HTML"
        );
        post("/sendMessage", body);
    }

    /**
     * Send a message with inline keyboard buttons.
     * Each button: Map.of("text", "Label", "callback_data", "btn_id")
     */
    public void sendButtons(Long chatId, String text, List<Map<String, String>> buttons) {
        List<List<Map<String, String>>> keyboard = buttons.stream()
                .map(b -> List.of(Map.of(
                        "text", b.get("text"),
                        "callback_data", b.get("callback_data")
                )))
                .toList();

        Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "text", text,
                "parse_mode", "HTML",
                "reply_markup", Map.of("inline_keyboard", keyboard)
        );
        post("/sendMessage", body);
    }

    /**
     * Answer a callback query to dismiss the loading spinner on the button.
     * Must be called after every button tap, otherwise Telegram shows a spinner.
     */
    public void answerCallback(String callbackQueryId) {
        post("/answerCallbackQuery", Map.of("callback_query_id", callbackQueryId));
    }

    private void post(String path, Map<String, Object> body) {
        if (mockMode) {
            log.info("📤 [TELEGRAM MOCK] {}: {}", path, body);
            return;
        }
        try {
            log.info("📤 Telegram sending to {}: {}", path, body);
            String response = webClient.post()
                    .uri(path)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .map(errorBody -> {
                                        log.error("❌ Telegram API error body: {}", errorBody);
                                        return new RuntimeException("Telegram error: " + errorBody);
                                    }))
                    .bodyToMono(String.class)
                    .block();
            log.debug("✅ Telegram response for {}: {}", path, response);
        } catch (Exception e) {
            log.error("❌ Telegram API call failed for {}: {}", path, e.getMessage());
        }
    }
}
