package com.elocate.whatsapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around Meta WhatsApp Cloud API.
 * Sends text messages and interactive button messages.
 */
@Service
@Slf4j
public class WhatsAppApiClient {

    private final WebClient webClient;
    private final String phoneNumberId;

    public WhatsAppApiClient(
            @Value("${whatsapp.token}") String token,
            @Value("${whatsapp.phone-number-id}") String phoneNumberId,
            @Value("${whatsapp.api-url:https://graph.facebook.com/v19.0}") String apiUrl) {

        this.phoneNumberId = phoneNumberId;
        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** Send a plain text message */
    public void sendText(String to, String text) {
        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "text",
                "text", Map.of("body", text)
        );
        post(body);
    }

    /**
     * Send an interactive button message (max 3 buttons).
     * Each button: Map.of("id", "btn_id", "title", "Button Label")
     */
    public void sendButtons(String to, String bodyText, List<Map<String, String>> buttons) {
        List<Map<String, Object>> buttonList = buttons.stream()
                .map(b -> Map.<String, Object>of(
                        "type", "reply",
                        "reply", Map.of("id", b.get("id"), "title", b.get("title"))
                ))
                .toList();

        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", bodyText),
                        "action", Map.of("buttons", buttonList)
                )
        );
        post(body);
    }

    /**
     * Send a WhatsApp Message Template (required for proactive/outbound messages).
     * The template must be pre-approved in Meta Business Manager.
     *
     * templateName : e.g. "driver_pickup_assigned"
     * languageCode : e.g. "en"
     * components   : list of template component objects (header, body params, buttons)
     */
    public void sendTemplate(String to, String templateName, String languageCode,
                             List<Map<String, Object>> components) {
        Map<String, Object> template = new java.util.LinkedHashMap<>();
        template.put("name", templateName);
        template.put("language", Map.of("code", languageCode));
        if (components != null && !components.isEmpty()) {
            template.put("components", components);
        }

        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "template",
                "template", template
        );
        post(body);
    }

    /**
     * Convenience: send the driver_pickup_assigned template.
     * Template body params order: {{1}}=requestNumber, {{2}}=deviceName,
     *   {{3}}=address, {{4}}=pickupDate, {{5}}=estimatedAmount
     * Buttons: Accept | Reject | In Progress
     *
     * NOTE: Until the template is approved by Meta, this falls back to a
     * plain interactive button message so development/testing still works.
     */
    public void sendPickupAssignedNotification(String to, String requestNumber,
            String deviceName, String address, String pickupDate,
            String estimatedAmount, String comments, boolean useTemplate) {

        if (useTemplate) {
            List<Map<String, Object>> params = List.of(
                    Map.of("type", "text", "text", requestNumber),
                    Map.of("type", "text", "text", deviceName),
                    Map.of("type", "text", "text", address),
                    Map.of("type", "text", "text", pickupDate),
                    Map.of("type", "text", "text", estimatedAmount)
            );
            List<Map<String, Object>> components = List.of(
                    Map.of("type", "body", "parameters", params)
            );
            sendTemplate(to, "driver_pickup_assigned", "en", components);
        } else {
            // Fallback: interactive buttons (works without template approval — for dev/testing)
            String body = "🆕 *New Pickup Assigned!*\n\n" +
                    "Request: *" + requestNumber + "*\n" +
                    "📱 Device: " + deviceName + "\n" +
                    "📍 Address: " + address + "\n" +
                    "📅 Pickup Date: " + pickupDate + "\n" +
                    "💰 Estimated Value: " + estimatedAmount +
                    (comments != null && !comments.isBlank() ? "\n\n📝 Note: " + comments : "") +
                    "\n\nWhat would you like to do?";

            sendButtons(to, body, List.of(
                    Map.of("id", "accept_pickup:" + requestNumber,  "title", "✅ Accept"),
                    Map.of("id", "reject_pickup:" + requestNumber,  "title", "❌ Reject"),
                    Map.of("id", "start_pickup:" + requestNumber,   "title", "🚗 In Progress")
            ));
        }
    }

    private void post(Map<String, Object> body) {
        try {
            String response = webClient.post()
                    .uri("/" + phoneNumberId + "/messages")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.debug("WhatsApp API response: {}", response);
        } catch (Exception e) {
            log.error("WhatsApp API call failed: {}", e.getMessage());
        }
    }
}
