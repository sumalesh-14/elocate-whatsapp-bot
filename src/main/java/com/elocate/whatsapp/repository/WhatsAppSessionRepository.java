package com.elocate.whatsapp.repository;

import com.elocate.whatsapp.model.WhatsAppSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WhatsAppSessionRepository extends JpaRepository<WhatsAppSession, String> {
}
