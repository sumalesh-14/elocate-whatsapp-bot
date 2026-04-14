package com.elocate.whatsapp.service;

import com.elocate.whatsapp.model.WhatsAppSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Generates and sends OTP via Brevo HTTP API (not SMTP).
 * Render free tier blocks outbound SMTP port 587, so we use the REST API instead.
 */
@Service
@Slf4j
public class OtpService {

    @Value("${app.email.from}")
    private String fromEmail;

    @Value("${app.otp.expiry-minutes:10}")
    private int expiryMinutes;

    @Value("${brevo.api.key}")
    private String brevoApiKey;

    private static final SecureRandom random = new SecureRandom();
    private static final String BREVO_URL = "https://api.brevo.com/v3/smtp/email";
    private final RestTemplate restTemplate = new RestTemplate();

    /** Generate OTP, store in session, send HTML email via Brevo API */
    public void generateAndSend(WhatsAppSession session, String toEmail) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        session.setPendingOtp(code);
        session.setOtpExpiresAt(LocalDateTime.now().plusMinutes(expiryMinutes));

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("api-key", brevoApiKey);

            Map<String, Object> body = Map.of(
                    "sender",      Map.of("name", "ELocate Driver Bot", "email", fromEmail),
                    "to",          List.of(Map.of("email", toEmail)),
                    "subject",     "ELocate — Your Driver Login OTP",
                    "htmlContent", buildHtml(code)
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(BREVO_URL, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("OTP email sent to {} via Brevo API", toEmail);
            } else {
                log.error("Brevo API failed for {}: status={} body={}", toEmail,
                        response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("Failed to send OTP email to {}: {}", toEmail, e.getMessage());
        }
    }

    /** Returns true if the code matches and has not expired */
    public boolean verify(WhatsAppSession session, String inputCode) {
        if (session.getPendingOtp() == null || session.getOtpExpiresAt() == null) return false;
        if (LocalDateTime.now().isAfter(session.getOtpExpiresAt())) return false;
        return session.getPendingOtp().equals(inputCode.trim());
    }

    /** Clear OTP fields after successful verification */
    public void clear(WhatsAppSession session) {
        session.setPendingOtp(null);
        session.setOtpExpiresAt(null);
        session.setPendingEmail(null);
    }

    private String buildHtml(String code) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8"/>
              <style>
                body { font-family: Arial, sans-serif; background: #f4f4f4; margin: 0; padding: 0; }
                .container { max-width: 480px; margin: 40px auto; background: #ffffff;
                             border-radius: 10px; overflow: hidden;
                             box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                .header { background: #1a7a4a; padding: 28px 32px; text-align: center; }
                .header h1 { color: #ffffff; margin: 0; font-size: 22px; letter-spacing: 1px; }
                .body { padding: 32px; color: #333333; }
                .body p { font-size: 15px; line-height: 1.6; margin: 0 0 16px; }
                .otp-box { background: #f0faf4; border: 2px dashed #1a7a4a;
                           border-radius: 8px; text-align: center; padding: 20px; margin: 24px 0; }
                .otp-box span { font-size: 38px; font-weight: bold;
                                letter-spacing: 10px; color: #1a7a4a; }
                .footer { background: #f9f9f9; padding: 16px 32px;
                          text-align: center; font-size: 12px; color: #999999; }
              </style>
            </head>
            <body>
              <div class="container">
                <div class="header"><h1>🌿 ELocate Driver Bot</h1></div>
                <div class="body">
                  <p>Hello Driver,</p>
                  <p>Your login OTP for the <strong>ELocate Telegram Bot</strong> is:</p>
                  <div class="otp-box"><span>%s</span></div>
                  <p>This code expires in <strong>%d minutes</strong>.</p>
                  <p>If you did not request this, please ignore this email.</p>
                </div>
                <div class="footer">&copy; ELocate &nbsp;|&nbsp; E-Waste Recycling Platform</div>
              </div>
            </body>
            </html>
            """.formatted(code, expiryMinutes);
    }
}
