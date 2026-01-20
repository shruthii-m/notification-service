package com.notification.notification.service;

import com.notification.notification.dto.NotificationRequest;
import com.notification.notification.dto.NotificationResponse;
import com.notification.notification.entity.Notification;
import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.entity.NotificationType;
import com.notification.notification.exception.ResourceNotFoundException;
import com.notification.notification.messaging.dto.NotificationMessage;
import com.notification.notification.messaging.producer.EventProducer;
import com.notification.notification.messaging.producer.NotificationProducer;
import com.notification.notification.repository.NotificationRepository;
import com.notification.notification.validator.NotificationRequestValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationProducer notificationProducer;
    private final EventProducer eventProducer;
    private final NotificationRequestValidator validator;

    @Override
    public NotificationResponse createNotification(NotificationRequest request) {
        log.info("Creating notification for recipient: {}", request.getRecipient());

        // Validate the notification request
        validator.validate(request);

        // Create and save notification with PENDING status
        Notification notification = Notification.builder()
                .title(request.getTitle())
                .message(request.getMessage())
                .recipient(request.getRecipient())
                .recipientEmail(request.getRecipientEmail())
                .type(request.getType())
                .status(NotificationStatus.PENDING)
                .organizationId(request.getOrganizationId())
                .build();

        Notification savedNotification = notificationRepository.save(notification);
        log.info("Notification created with ID: {}, UUID: {}",
                savedNotification.getId(), savedNotification.getUuid());

        // Create Kafka message
        NotificationMessage message = NotificationMessage.builder()
                .notificationUuid(savedNotification.getUuid())
                .notificationId(savedNotification.getId())
                .organizationId(savedNotification.getOrganizationId())
                .title(savedNotification.getTitle())
                .message(savedNotification.getMessage())
                .recipient(savedNotification.getRecipient())
                .recipientEmail(savedNotification.getRecipientEmail())
                .type(savedNotification.getType())
                .retryCount(0)
                .maxRetries(savedNotification.getMaxRetries())
                .createdAt(savedNotification.getCreatedAt())
                .correlationId(savedNotification.getUuid().toString())
                .build();

        // Publish to Kafka for async processing
        notificationProducer.publishToSend(message);
        log.info("Notification {} published to Kafka for async processing", savedNotification.getUuid());

        // Publish created event
        eventProducer.publishCreated(savedNotification.getUuid(), savedNotification.getOrganizationId());

        return mapToResponse(savedNotification);
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationResponse getNotificationById(Long id) {
        log.info("Fetching notification with ID: {}", id);
        Notification notification = findNotificationById(id);
        return mapToResponse(notification);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getAllNotifications() {
        log.info("Fetching all notifications");
        return notificationRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getAllNotifications(Pageable pageable) {
        log.info("Fetching all notifications with pagination");
        return notificationRepository.findAll(pageable)
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotificationsByRecipient(String recipient) {
        log.info("Fetching notifications for recipient: {}", recipient);
        return notificationRepository.findByRecipientOrderByCreatedAtDesc(recipient).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotificationsByRecipient(String recipient, Pageable pageable) {
        log.info("Fetching notifications for recipient: {} with pagination", recipient);
        return notificationRepository.findByRecipient(recipient, pageable)
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotificationsByStatus(NotificationStatus status) {
        log.info("Fetching notifications with status: {}", status);
        return notificationRepository.findByStatus(status).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotificationsByType(NotificationType type) {
        log.info("Fetching notifications with type: {}", type);
        return notificationRepository.findByType(type).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public NotificationResponse updateNotification(Long id, NotificationRequest request) {
        log.info("Updating notification with ID: {}", id);

        Notification notification = findNotificationById(id);
        notification.setTitle(request.getTitle());
        notification.setMessage(request.getMessage());
        notification.setRecipient(request.getRecipient());
        notification.setRecipientEmail(request.getRecipientEmail());
        notification.setType(request.getType());

        Notification updatedNotification = notificationRepository.save(notification);
        log.info("Notification updated with ID: {}", updatedNotification.getId());

        return mapToResponse(updatedNotification);
    }

    @Override
    public NotificationResponse updateNotificationStatus(Long id, NotificationStatus status) {
        log.info("Updating notification status for ID: {} to {}", id, status);

        Notification notification = findNotificationById(id);
        notification.setStatus(status);

        if (status == NotificationStatus.SENT) {
            notification.setSentAt(LocalDateTime.now());
        } else if (status == NotificationStatus.READ) {
            notification.setReadAt(LocalDateTime.now());
        }

        Notification updatedNotification = notificationRepository.save(notification);
        return mapToResponse(updatedNotification);
    }

    @Override
    public NotificationResponse markAsRead(Long id) {
        log.info("Marking notification as read: {}", id);
        return updateNotificationStatus(id, NotificationStatus.READ);
    }

    @Override
    public NotificationResponse markAsSent(Long id) {
        log.info("Marking notification as sent: {}", id);
        return updateNotificationStatus(id, NotificationStatus.SENT);
    }

    @Override
    public void deleteNotification(Long id) {
        log.info("Deleting notification with ID: {}", id);
        Notification notification = findNotificationById(id);
        notificationRepository.delete(notification);
        log.info("Notification deleted with ID: {}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnreadNotifications(String recipient) {
        log.info("Counting unread notifications for recipient: {}", recipient);
        return notificationRepository.countByRecipientAndStatus(recipient, NotificationStatus.PENDING);
    }

    private Notification findNotificationById(Long id) {
        return notificationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", "id", id));
    }

    private NotificationResponse mapToResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .uuid(notification.getUuid())
                .organizationId(notification.getOrganizationId())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .recipient(notification.getRecipient())
                .recipientEmail(notification.getRecipientEmail())
                .type(notification.getType())
                .status(notification.getStatus())
                .retryCount(notification.getRetryCount())
                .maxRetries(notification.getMaxRetries())
                .errorCode(notification.getErrorCode())
                .errorMessage(notification.getErrorMessage())
                .providerId(notification.getProviderId())
                .providerName(notification.getProviderName())
                .createdAt(notification.getCreatedAt())
                .sentAt(notification.getSentAt())
                .readAt(notification.getReadAt())
                .build();
    }
}

