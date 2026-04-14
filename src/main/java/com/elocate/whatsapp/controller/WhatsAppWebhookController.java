package com.elocate.whatsapp.controller;

import com.elocate.whatsapp.dto.WhatsAppWebhookPayload;
import com.elocate.whatsapp.service.WhatsAppBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/whatsapp/webhook")
@Slf4j
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private final WhatsAppBotService botService;

    @Value("${whatsapp.verify-token}")
    private String verifyToken;

    // -------------------------------------------------------------------------
    // GET — Meta webhook verification (one-time setup)
    // -------------------------------------------------------------------------
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("WhatsApp webhook verified successfully");
            return ResponseEntity.ok(challenge);
        }
        log.warn("WhatsApp webhook verification failed");
        return ResponseEntity.status(403).body("Forbidden");
    }

    // -------------------------------------------------------------------------
    // POST — incoming messages from drivers
    // -------------------------------------------------------------------------
    @PostMapping
    public ResponseEntity<String> receive(@RequestBody WhatsAppWebhookPayload payload) {
        try {
            if (payload.getEntry() == null) return ResponseEntity.ok("ok");

            for (var entry : payload.getEntry()) {
                if (entry.getChanges() == null) continue;
                for (var change : entry.getChanges()) {
                    if (change.getValue() == null) continue;
                    List<WhatsAppWebhookPayload.Message> messages = change.getValue().getMessages();
                    if (messages == null) continue;

                    for (var msg : messages) {
                        String phone = msg.getFrom();
                        String textBody = null;
                        String interactiveId = null;

                        if ("text".equals(msg.getType()) && msg.getText() != null) {
                            textBody = msg.getText().getBody();
                        } else if ("interactive".equals(msg.getType())
                                && msg.getInteractive() != null
                                && msg.getInteractive().getButtonReply() != null) {
                            interactiveId = msg.getInteractive().getButtonReply().getId();
                        }

                        log.info("Incoming from {}: text={} interactive={}", phone, textBody, interactiveId);
                        botService.handleMessage(phone, textBody, interactiveId);
                    }
                }
            }
        } catch (Exception e) {
            // Always return 200 to Meta — otherwise it retries endlessly
            log.error("Error processing webhook: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok("ok");
    }
}
