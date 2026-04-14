package com.elocate.whatsapp.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;

@Entity
@Table(name = "user_address")
@Data
public class UserAddress {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    private String address;
    private String city;
    private String state;
    private String pincode;
}
