package com.elocate.whatsapp.controller;

import com.elocate.whatsapp.dto.PickupAssignedNotificationRequest;
import com.elocate.whatsapp.service.WhatsAppApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal endpoint called by elocate-server to push WhatsApp notifications.
 * Protected by a shared secret header so only elocate-server can call it.
 */
@RestController
@RequestMapping("/internal/notify")
@Slf4j
@RequiredArgsConstructor
public class NotificationController {

    private final WhatsAppApiClient apiClient;

    @Value("${internal.secret:elocate-internal-secret}")
    private String internalSecret;

    @Value("${whatsapp.use-template:false}")
    private boolean useTemplate;

    /**
     * Called by elocate-server immediately after a driver is assigned to a pickup.
     * Header: X-Internal-Secret must match the shared secret.
     */
    @PostMapping("/pickup-assigned")
    public ResponseEntity<Map<String, String>> pickupAssigned(
            @RequestHeader("X-Internal-Secret") String secret,
            @RequestBody PickupAssignedNotificationRequest req) {

        if (!internalSecret.equals(secret)) {
            log.warn("Unauthorized internal notification attempt");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        log.info("Sending pickup-assigned WhatsApp notification to driver {} for request {}",
                req.getDriverPhone(), req.getRequestNumber());

        apiClient.sendPickupAssignedNotification(
                req.getDriverPhone(),
                req.getRequestNumber(),
                req.getDeviceName(),
                req.getPickupAddress(),
                req.getPickupDate(),
                req.getEstimatedAmount(),
                req.getComments(),
                useTemplate
        );

        return ResponseEntity.ok(Map.of("status", "sent"));
    }
}
