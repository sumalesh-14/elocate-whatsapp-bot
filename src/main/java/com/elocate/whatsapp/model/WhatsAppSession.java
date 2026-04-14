package com.elocate.whatsapp.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stores per-driver conversation state for the WhatsApp bot.
 * Keyed by the driver's WhatsApp phone number (e.g. "919876543210").
 */
@Entity
@Table(name = "whatsapp_session")
@Data
public class WhatsAppSession {

    @Id
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    /** Set after OTP is verified — links phone to driver */
    @Column(name = "driver_id")
    private UUID driverId;

    /**
     * Conversation state:
     * AWAITING_EMAIL | AWAITING_OTP | MAIN_MENU | PENDING_LIST | COMPLETED_LIST | PROFILE | AWAITING_REJECT_REASON
     */
    @Column(name = "state", length = 30, nullable = false)
    private String state = "AWAITING_EMAIL";

    /** Temporarily holds email while waiting for OTP confirmation */
    @Column(name = "pending_email")
    private String pendingEmail;

    /** 6-digit OTP code */
    @Column(name = "pending_otp", length = 10)
    private String pendingOtp;

    /** When the OTP expires */
    @Column(name = "otp_expires_at")
    private LocalDateTime otpExpiresAt;

    /** Pagination offset for list views */
    @Column(name = "list_offset", nullable = false)
    private int listOffset = 0;

    /** Holds the request number while waiting for driver to type a reject reason or accept remarks */
    @Column(name = "pending_action_request")
    private String pendingActionRequest;

    /** Holds the pending action type: ACCEPT or REJECT */
    @Column(name = "pending_action", length = 20)
    private String pendingAction;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
