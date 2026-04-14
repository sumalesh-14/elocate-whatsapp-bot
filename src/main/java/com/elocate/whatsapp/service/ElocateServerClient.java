package com.elocate.whatsapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * HTTP client that calls elocate-server's internal endpoints to perform
 * driver pickup actions. This ensures ALL business logic (status history,
 * citizen emails, token invalidation, LOCKED status) runs in elocate-server
 * exactly as it does for the email-link flow.
 */
@Service
@Slf4j
public class ElocateServerClient {

    private final WebClient webClient;
    private final String internalSecret;

    public ElocateServerClient(
            @Value("${elocate.server.url:http://localhost:8080}") String serverUrl,
            @Value("${internal.secret:elocate-internal-secret}") String internalSecret) {

        this.internalSecret = internalSecret;
        this.webClient = WebClient.builder()
                .baseUrl(serverUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Step 1: resolve requestNumber → active token for the given action.
     * Returns null if no valid token found (e.g. already used or expired).
     */
    public String resolveToken(String requestNumber, String actionType) {
        try {
            log.info("🔍 resolveToken → GET /internal/driver/token/{}/{}", requestNumber, actionType);
            Map response = webClient.get()
                    .uri("/internal/driver/token/" + requestNumber + "/" + actionType)
                    .header("X-Internal-Secret", internalSecret)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .map(body -> {
                                        log.error("❌ resolveToken error {} for {}/{}: {}",
                                                clientResponse.statusCode(), requestNumber, actionType, body);
                                        return new RuntimeException(body);
                                    }))
                    .bodyToMono(Map.class)
                    .block();

            log.info("✅ resolveToken response for {}/{}: {}", requestNumber, actionType, response);

            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                return (String) response.get("token");
            }
            log.warn("⚠️ No valid token for {}/{}: {}", requestNumber, actionType,
                    response != null ? response.get("error") : "null response");
            return null;
        } catch (Exception e) {
            log.error("❌ resolveToken failed for {}/{}: {}", requestNumber, actionType, e.getMessage());
            return null;
        }
    }

    /** Mark pickup as IN_PROGRESS — records history in elocate-server */
    public boolean markInProgress(String token) {
        log.info("🚗 markInProgress → POST /internal/driver/in-progress/{}", token);
        return post("/internal/driver/in-progress/" + token, null);
    }

    /** Accept pickup — records history + emails citizen via elocate-server */
    public boolean acceptPickup(String token) {
        log.info("✅ acceptPickup → POST /internal/driver/accept/{}", token);
        return post("/internal/driver/accept/" + token,
                Map.of("comments", "Accepted via Telegram"));
    }

    /** Accept pickup with custom remarks */
    public boolean acceptPickupWithRemarks(String token, String remarks) {
        log.info("✅ acceptPickupWithRemarks → POST /internal/driver/accept/{} remarks={}", token, remarks);
        return post("/internal/driver/accept/" + token,
                Map.of("comments", remarks != null ? remarks : "Accepted via Telegram"));
    }

    /** Reject pickup — records history + emails citizen + sets LOCKED via elocate-server */
    public boolean rejectPickup(String token, String reason) {
        log.info("❌ rejectPickup → POST /internal/driver/reject/{} reason={}", token, reason);
        return post("/internal/driver/reject/" + token,
                Map.of("reason", reason));
    }

    private boolean post(String uri, Object body) {
        try {
            var req = webClient.post()
                    .uri(uri)
                    .header("X-Internal-Secret", internalSecret);

            Map response = (body != null
                    ? req.bodyValue(body)
                    : req.bodyValue(Map.of()))
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .map(errorBody -> {
                                        log.error("❌ elocate-server error {} for {}: {}",
                                                clientResponse.statusCode(), uri, errorBody);
                                        return new RuntimeException(errorBody);
                                    }))
                    .bodyToMono(Map.class)
                    .block();

            log.info("✅ elocate-server response for {}: {}", uri, response);
            boolean success = response != null && Boolean.TRUE.equals(response.get("success"));
            if (!success) log.warn("⚠️ elocate-server returned failure for {}: {}", uri,
                    response != null ? response.get("error") : "null");
            return success;
        } catch (Exception e) {
            log.error("❌ elocate-server call failed for {}: {}", uri, e.getMessage());
            return false;
        }
    }
}
