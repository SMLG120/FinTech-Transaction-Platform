package com.fintech.platform.notification.persistence;

import com.fintech.platform.notification.domain.NotificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    Page<NotificationEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<NotificationEntity> findByStatusOrderByCreatedAtDesc(NotificationStatus status, Pageable pageable);

    /**
     * The retry scheduler's work queue: failed deliveries whose next attempt is due.
     *
     * <p>Bounded by the caller, because an unbounded list of due rows is how a scheduler turns a
     * provider outage into an out-of-memory event.
     */
    List<NotificationEntity> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            NotificationStatus status, Instant due);

    List<NotificationEntity> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);
}
