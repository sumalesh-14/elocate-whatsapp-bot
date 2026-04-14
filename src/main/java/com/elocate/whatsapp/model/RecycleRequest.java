package com.elocate.whatsapp.model;

import com.elocate.whatsapp.model.enums.FulfillmentStatus;
import com.elocate.whatsapp.model.enums.FulfillmentType;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "recycle_request")
@Data
public class RecycleRequest {

    @Id
    private UUID id;

    @Column(name = "request_number")
    private String requestNumber;

    @Column(name = "user_id")
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_model_id")
    private DeviceModel deviceModel;

    @Column(name = "condition_code")
    private String conditionCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pickup_address_id")
    private UserAddress pickupAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_type")
    private FulfillmentType fulfillmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status")
    private FulfillmentStatus fulfillmentStatus;

    @Column(name = "assigned_driver_id")
    private UUID assignedDriverId;

    @Enumerated(EnumType.STRING)
    private RecycleStatus status;

    @Column(name = "pickup_date")
    private LocalDate pickupDate;

    @Column(name = "estimated_amount", precision = 10, scale = 2)
    private BigDecimal estimatedAmount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
