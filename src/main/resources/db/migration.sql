-- Run this once against your Neon DB before starting the service.
-- The whatsapp_session table is the ONLY new table this service creates.
-- All other tables (driver, recycle_request, etc.) already exist.

CREATE TABLE IF NOT EXISTS whatsapp_session (
    phone_number           VARCHAR(20)  PRIMARY KEY,
    driver_id              UUID,
    state                  VARCHAR(30)  NOT NULL DEFAULT 'AWAITING_EMAIL',
    pending_email          VARCHAR(255),
    pending_otp            VARCHAR(10),
    otp_expires_at         TIMESTAMP,
    list_offset            INT          NOT NULL DEFAULT 0,
    pending_action_request VARCHAR(50),
    pending_action         VARCHAR(20),
    updated_at             TIMESTAMP    DEFAULT NOW()
);
