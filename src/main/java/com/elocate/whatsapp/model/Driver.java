package com.elocate.whatsapp.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;

@Entity
@Table(name = "driver")
@Data
public class Driver {

    @Id
    private UUID id;

    private String name;
    private String email;
    private String phone;

    @Column(name = "vehicle_number")
    private String vehicleNumber;

    @Column(name = "vehicle_type")
    private String vehicleType;

    private String availability;

    @Column(name = "facility_id")
    private UUID facilityId;
}
