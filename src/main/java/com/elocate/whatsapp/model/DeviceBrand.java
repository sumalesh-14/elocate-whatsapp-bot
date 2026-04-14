package com.elocate.whatsapp.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;

@Entity
@Table(name = "device_brand")
@Data
public class DeviceBrand {

    @Id
    private UUID id;

    private String code;
    private String name;
}
