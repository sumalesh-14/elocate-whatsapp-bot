package com.elocate.whatsapp.dto;

import lombok.Data;

/**
 * Payload sent from elocate-server to this bot service
 * when a driver is assigned to a pickup request.
 */
@Data
public class PickupAssignedNotificationRequest {
    private String requestNumber;       // e.g. EL-2024-00145
    private String requestId;           // UUID string
    private String driverId;            // driver UUID — used to find Telegram session
    private String driverPhone;         // driver's WhatsApp number e.g. "919876543210"
    private String driverName;
    private String deviceName;          // e.g. "iPhone 14 Pro Max"
    private String pickupAddress;       // full address string
    private String pickupDate;          // e.g. "Apr 18, 2024"
    private String estimatedAmount;     // e.g. "₹12,500"
    private String comments;            // optional intermediary instructions
}
