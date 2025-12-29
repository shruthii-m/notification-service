package com.notification.notification.service;

import com.notification.notification.dto.NotificationRequest;
import com.notification.notification.dto.NotificationResponse;
import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.entity.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface NotificationService {

    NotificationResponse createNotification(NotificationRequest request);

    NotificationResponse getNotificationById(Long id);

    List<NotificationResponse> getAllNotifications();

    Page<NotificationResponse> getAllNotifications(Pageable pageable);

    List<NotificationResponse> getNotificationsByRecipient(String recipient);

    Page<NotificationResponse> getNotificationsByRecipient(String recipient, Pageable pageable);

    List<NotificationResponse> getNotificationsByStatus(NotificationStatus status);

    List<NotificationResponse> getNotificationsByType(NotificationType type);

    NotificationResponse updateNotification(Long id, NotificationRequest request);

    NotificationResponse updateNotificationStatus(Long id, NotificationStatus status);

    NotificationResponse markAsRead(Long id);

    NotificationResponse markAsSent(Long id);

    void deleteNotification(Long id);

    long countUnreadNotifications(String recipient);
}

