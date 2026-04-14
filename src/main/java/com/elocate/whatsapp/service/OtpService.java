package com.elocate.whatsapp.service;

import com.elocate.whatsapp.model.WhatsAppSession;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
@Slf4j
public class OtpService {

    private final JavaMailSender mailSender;

    @Value("${app.email.from}")
    private String fromEmail;

    @Value("${app.otp.expiry-minutes:10}")
    private int expiryMinutes;

    private static final SecureRandom random = new SecureRandom();

    public OtpService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Generate OTP, store it in the session object, and send HTML email to the driver */
    public void generateAndSend(WhatsAppSession session, String toEmail) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        session.setPendingOtp(code);
        session.setOtpExpiresAt(LocalDateTime.now().plusMinutes(expiryMinutes));

        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("ELocate — Your Driver Login OTP");
            helper.setText(buildHtml(code), true); // true = isHtml
            mailSender.send(mime);
            log.info("OTP HTML email sent to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send OTP email to {}: {}", toEmail, e.getMessage());
        }
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
                <div class="header">
                  <h1>🌿 ELocate Driver Bot</h1>
                </div>
                <div class="body">
                  <p>Hello Driver,</p>
                  <p>You requested to log in to the <strong>ELocate WhatsApp Bot</strong>.
                     Use the OTP below to verify your identity:</p>
                  <div class="otp-box">
                    <span>%s</span>
                  </div>
                  <p>This code expires in <strong>%d minutes</strong>.</p>
                  <p>If you did not request this, please ignore this email.
                     Your account remains secure.</p>
                </div>
                <div class="footer">
                  &copy; ELocate &nbsp;|&nbsp; E-Waste Recycling Platform
                </div>
              </div>
            </body>
            </html>
            """.formatted(code, expiryMinutes);
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
}
