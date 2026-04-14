package com.elocate.whatsapp.model.enums;

public enum FulfillmentStatus {
    PICKUP_REQUESTED,
    PICKUP_ASSIGNED,
    PICKUP_IN_PROGRESS,
    PICKUP_COMPLETED,
    PICKUP_FAILED,
    DROP_PENDING,
    DROPPED_AT_FACILITY,
    DROP_VERIFIED,
    REJECTED;

    public boolean isPickupStatus() {
        return this.name().startsWith("PICKUP_");
    }
}
