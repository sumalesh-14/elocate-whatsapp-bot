package com.elocate.whatsapp.controller;

import com.elocate.whatsapp.dto.TelegramWebhookPayload;
import com.elocate.whatsapp.service.TelegramApiClient;
import com.elocate.whatsapp.service.TelegramBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives all updates from Telegram via webhook.
 * Telegram sends a POST to this endpoint for every message or button tap.
 *
 * Register webhook once:
 *   GET https://api.telegram.org/bot{TOKEN}/setWebhook?url=https://yourdomain.com/api/telegram/webhook
 */
@RestController
@RequestMapping("/api/telegram/webhook")
@Slf4j
@RequiredArgsConstructor
public class TelegramWebhookController {

    private final TelegramBotService botService;
    private final TelegramApiClient apiClient;

    @PostMapping
    public ResponseEntity<String> receive(@RequestBody TelegramWebhookPayload payload) {
        try {
            // ── Inline button tap ──────────────────────────────────────────
            if (payload.getCallbackQuery() != null) {
                var cb = payload.getCallbackQuery();
                Long chatId = cb.getFrom().getId();
                String data = cb.getData();

                log.info("Telegram callback from {}: data={}", chatId, data);

                // Acknowledge the button tap immediately (removes spinner)
                apiClient.answerCallback(cb.getId());

                botService.handleMessage(chatId, null, data);

            // ── Text message ───────────────────────────────────────────────
            } else if (payload.getMessage() != null) {
                var msg = payload.getMessage();
                Long chatId = msg.getChat().getId();
                String text = msg.getText();

                log.info("Telegram message from {}: text={}", chatId, text);

                // Ignore non-text messages (photos, stickers, etc.)
                if (text == null || text.isBlank()) return ResponseEntity.ok("ok");

                botService.handleMessage(chatId, text, null);
            }

        } catch (Exception e) {
            // Always return 200 — Telegram retries on non-200 responses
            log.error("Error processing Telegram update: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok("ok");
    }
}
