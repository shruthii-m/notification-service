package com.notification.notification.repository;

import com.notification.notification.entity.Notification;
import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.entity.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByRecipient(String recipient);

    Page<Notification> findByRecipient(String recipient, Pageable pageable);

    List<Notification> findByRecipientAndStatus(String recipient, NotificationStatus status);

    List<Notification> findByStatus(NotificationStatus status);

    List<Notification> findByType(NotificationType type);

    List<Notification> findByRecipientAndType(String recipient, NotificationType type);

    @Query("SELECT n FROM Notification n WHERE n.recipient = :recipient AND n.status = 'PENDING' ORDER BY n.createdAt DESC")
    List<Notification> findPendingNotifications(@Param("recipient") String recipient);

    @Query("SELECT n FROM Notification n WHERE n.createdAt BETWEEN :startDate AND :endDate")
    List<Notification> findByDateRange(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.recipient = :recipient AND n.status = :status")
    long countByRecipientAndStatus(
            @Param("recipient") String recipient,
            @Param("status") NotificationStatus status
    );

    List<Notification> findByRecipientOrderByCreatedAtDesc(String recipient);
}

