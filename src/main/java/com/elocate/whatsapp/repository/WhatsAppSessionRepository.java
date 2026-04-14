package com.elocate.whatsapp.repository;

import com.elocate.whatsapp.model.WhatsAppSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WhatsAppSessionRepository extends JpaRepository<WhatsAppSession, String> {
    List<WhatsAppSession> findByDriverId(UUID driverId);
}
