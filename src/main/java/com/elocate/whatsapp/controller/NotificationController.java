package com.elocate.whatsapp.controller;

import com.elocate.whatsapp.dto.PickupAssignedNotificationRequest;
import com.elocate.whatsapp.model.WhatsAppSession;
import com.elocate.whatsapp.repository.WhatsAppSessionRepository;
import com.elocate.whatsapp.service.TelegramApiClient;
import com.elocate.whatsapp.service.WhatsAppApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal endpoint called by elocate-server when a driver is assigned a pickup.
 * Auto-routes to Telegram if the driver has a Telegram session, else WhatsApp.
 */
@RestController
@RequestMapping("/internal/notify")
@Slf4j
@RequiredArgsConstructor
public class NotificationController {

    private final WhatsAppApiClient whatsAppApiClient;
    private final TelegramApiClient telegramApiClient;
    private final WhatsAppSessionRepository sessionRepo;

    @Value("${internal.secret:elocate-internal-secret}")
    private String internalSecret;

    @Value("${whatsapp.use-template:false}")
    private boolean useTemplate;

    @PostMapping("/pickup-assigned")
    public ResponseEntity<Map<String, String>> pickupAssigned(
            @RequestHeader("X-Internal-Secret") String secret,
            @RequestBody PickupAssignedNotificationRequest req) {

        if (!internalSecret.equals(secret)) {
            log.warn("Unauthorized internal notification attempt");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        log.info("Pickup-assigned notification for driver: {} request: {}",
                req.getDriverPhone(), req.getRequestNumber());

        // Look for a Telegram session by driverId
        // Telegram sessions are keyed as "tg:{chatId}"
        UUID driverUuid = null;
        try {
            if (req.getDriverId() != null) driverUuid = UUID.fromString(req.getDriverId());
        } catch (Exception ignored) {}

        Optional<WhatsAppSession> telegramSession = Optional.empty();
        if (driverUuid != null) {
            telegramSession = sessionRepo.findByDriverId(driverUuid).stream()
                    .filter(s -> s.getPhoneNumber().startsWith("tg:"))
                    .findFirst();
        }

        if (telegramSession.isPresent()) {
            Long chatId = Long.parseLong(telegramSession.get().getPhoneNumber().substring(3));
            sendTelegramNotification(chatId, req);
        } else {
            log.info("No Telegram session, routing to WhatsApp: {}", req.getDriverPhone());
            whatsAppApiClient.sendPickupAssignedNotification(
                    req.getDriverPhone(),
                    req.getRequestNumber(),
                    req.getDeviceName(),
                    req.getPickupAddress(),
                    req.getPickupDate(),
                    req.getEstimatedAmount(),
                    req.getComments(),
                    useTemplate
            );
        }

        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    private void sendTelegramNotification(Long chatId, PickupAssignedNotificationRequest req) {
        String msg = "🆕 <b>New Pickup Assigned!</b>\n\n" +
                "Request: <b>" + req.getRequestNumber() + "</b>\n" +
                "📱 Device: " + req.getDeviceName() + "\n" +
                "📍 Address: " + req.getPickupAddress() + "\n" +
                "📅 Pickup Date: " + req.getPickupDate() + "\n" +
                "💰 Estimated Value: " + req.getEstimatedAmount() +
                (req.getComments() != null && !req.getComments().isBlank()
                        ? "\n\n📝 Note: " + req.getComments() : "") +
                "\n\nWhat would you like to do?";

        telegramApiClient.sendButtons(chatId, msg, List.of(
                Map.of("text", "✅ Accept",      "callback_data", "accept_pickup:" + req.getRequestNumber()),
                Map.of("text", "❌ Reject",      "callback_data", "reject_pickup:" + req.getRequestNumber()),
                Map.of("text", "🚗 In Progress", "callback_data", "start_pickup:"  + req.getRequestNumber())
        ));
    }
}
