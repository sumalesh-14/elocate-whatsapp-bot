package com.elocate.whatsapp.repository;

import com.elocate.whatsapp.model.RecycleRequest;
import com.elocate.whatsapp.model.enums.FulfillmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecycleRequestRepository extends JpaRepository<RecycleRequest, UUID> {

    Optional<RecycleRequest> findByRequestNumber(String requestNumber);

    /** Pending pickups: PICKUP_ASSIGNED or PICKUP_IN_PROGRESS */
    Page<RecycleRequest> findByAssignedDriverIdAndFulfillmentStatusIn(
            UUID driverId, List<FulfillmentStatus> statuses, Pageable pageable);

    /** Completed pickups: PICKUP_COMPLETED */
    Page<RecycleRequest> findByAssignedDriverIdAndFulfillmentStatus(
            UUID driverId, FulfillmentStatus status, Pageable pageable);
}
