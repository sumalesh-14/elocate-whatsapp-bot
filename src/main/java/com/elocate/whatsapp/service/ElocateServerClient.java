package com.elocate.whatsapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * HTTP client that calls elocate-server's open internal endpoints.
 * No secret header needed — endpoints are open (secured at infra level).
 */
@Service
@Slf4j
public class ElocateServerClient {

    private final WebClient webClient;

    public ElocateServerClient(
            @Value("${elocate.server.url:http://localhost:8080}") String serverUrl) {

        this.webClient = WebClient.builder()
                .baseUrl(serverUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** Resolve requestNumber → active token for the given action type */
    public String resolveToken(String requestNumber, String actionType) {
        try {
            log.info("🔍 resolveToken → GET /internal/driver/token/{}/{}", requestNumber, actionType);
            Map response = webClient.get()
                    .uri("/internal/driver/token/" + requestNumber + "/" + actionType)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            cr -> cr.bodyToMono(String.class)
                                    .map(body -> {
                                        log.error("❌ resolveToken error {} for {}/{}: {}",
                                                cr.statusCode(), requestNumber, actionType, body);
                                        return new RuntimeException(body);
                                    }))
                    .bodyToMono(Map.class)
                    .block();

            log.info("✅ resolveToken response for {}/{}: {}", requestNumber, actionType, response);
            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                return (String) response.get("token");
            }
            log.warn("⚠️ No valid token for {}/{}: {}", requestNumber, actionType,
                    response != null ? response.get("error") : "null");
            return null;
        } catch (Exception e) {
            log.error("❌ resolveToken failed for {}/{}: {}", requestNumber, actionType, e.getMessage());
            return null;
        }
    }

    public boolean markInProgress(String token) {
        log.info("🚗 markInProgress → token={}", token);
        return post("/internal/driver/in-progress/" + token, null);
    }

    public boolean acceptPickup(String token) {
        log.info("✅ acceptPickup → token={}", token);
        return post("/internal/driver/accept/" + token, Map.of("comments", "Accepted via Telegram"));
    }

    public boolean acceptPickupWithRemarks(String token, String remarks) {
        log.info("✅ acceptPickupWithRemarks → token={} remarks={}", token, remarks);
        return post("/internal/driver/accept/" + token,
                Map.of("comments", remarks != null ? remarks : "Accepted via Telegram"));
    }

    public boolean rejectPickup(String token, String reason) {
        log.info("❌ rejectPickup → token={} reason={}", token, reason);
        return post("/internal/driver/reject/" + token, Map.of("reason", reason));
    }

    private boolean post(String uri, Object body) {
        try {
            var req = webClient.post().uri(uri);
            Map response = (body != null ? req.bodyValue(body) : req.bodyValue(Map.of()))
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            cr -> cr.bodyToMono(String.class)
                                    .map(errorBody -> {
                                        log.error("❌ elocate-server error {} for {}: {}",
                                                cr.statusCode(), uri, errorBody);
                                        return new RuntimeException(errorBody);
                                    }))
                    .bodyToMono(Map.class)
                    .block();

            log.info("✅ elocate-server response for {}: {}", uri, response);
            boolean success = response != null && Boolean.TRUE.equals(response.get("success"));
            if (!success) log.warn("⚠️ failure for {}: {}", uri,
                    response != null ? response.get("error") : "null");
            return success;
        } catch (Exception e) {
            log.error("❌ elocate-server call failed for {}: {}", uri, e.getMessage());
            return false;
        }
    }
}
