package com.notification.notification.messaging.consumer;

import com.notification.notification.entity.Notification;
import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.exception.PermanentSendException;
import com.notification.notification.exception.TransientSendException;
import com.notification.notification.messaging.dto.NotificationMessage;
import com.notification.notification.messaging.producer.EventProducer;
import com.notification.notification.messaging.producer.NotificationProducer;
import com.notification.notification.repository.NotificationRepository;
import com.notification.notification.sender.NotificationSender;
import com.notification.notification.sender.NotificationSenderFactory;
import com.notification.notification.sender.SendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SenderConsumer {

    private final NotificationRepository notificationRepository;
    private final NotificationSenderFactory senderFactory;
    private final NotificationProducer notificationProducer;
    private final EventProducer eventProducer;

    @Value("${kafka.retry.delay.level1}")
    private long retryDelay1;

    @Value("${kafka.retry.delay.level2}")
    private long retryDelay2;

    @Value("${kafka.retry.delay.level3}")
    private long retryDelay3;

    @Value("${kafka.retry.delay.level4}")
    private long retryDelay4;

    @Value("${kafka.retry.delay.level5}")
    private long retryDelay5;

    @KafkaListener(
        topics = "${kafka.topic.notification-send}",
        groupId = "notification-sender-group",
        concurrency = "3"
    )
    public void consumeSendMessage(
        ConsumerRecord<String, NotificationMessage> record,
        Acknowledgment ack
    ) {
        NotificationMessage message = record.value();
        log.info("Received message from topic: {}, key: {}, uuid: {}",
            record.topic(), record.key(), message.getNotificationUuid());

        try {
            // Fetch notification from database
            Optional<Notification> notificationOpt = notificationRepository.findByUuid(message.getNotificationUuid());

            if (notificationOpt.isEmpty()) {
                log.error("Notification not found for UUID: {}", message.getNotificationUuid());
                ack.acknowledge();
                return;
            }

            Notification notification = notificationOpt.get();

            // Idempotency check - skip if already sent
            if (notification.getStatus() == NotificationStatus.SENT) {
                log.info("Notification already sent, skipping: uuid={}", message.getNotificationUuid());
                ack.acknowledge();
                return;
            }

            // Update status to PROCESSING
            notification.setStatus(NotificationStatus.PROCESSING);
            notificationRepository.save(notification);

            // Get appropriate sender
            Optional<NotificationSender> senderOpt = senderFactory.getSender(notification.getType());

            if (senderOpt.isEmpty()) {
                log.error("No sender available for notification type: {}", notification.getType());
                handlePermanentFailure(notification, message, "No sender available for type: " + notification.getType());
                ack.acknowledge();
                return;
            }

            NotificationSender sender = senderOpt.get();

            // Publish send attempted event
            eventProducer.publishSendAttempted(
                notification.getUuid(),
                notification.getOrganizationId(),
                notification.getRetryCount()
            );

            // Attempt to send notification
            SendResult result = sender.send(notification);

            // Handle success
            if (result.isSuccess()) {
                handleSuccess(notification, result);
                ack.acknowledge();
            } else {
                // Should not happen as sender throws exceptions
                handlePermanentFailure(notification, message, result.getErrorMessage());
                ack.acknowledge();
            }

        } catch (TransientSendException e) {
            // Transient failure - schedule retry
            log.warn("Transient failure sending notification uuid={}, retryCount={}: {}",
                message.getNotificationUuid(), message.getRetryCount(), e.getMessage());

            try {
                Optional<Notification> notificationOpt = notificationRepository.findByUuid(message.getNotificationUuid());
                if (notificationOpt.isPresent()) {
                    Notification notification = notificationOpt.get();
                    handleTransientFailure(notification, message, e.getMessage());
                }
            } catch (Exception ex) {
                log.error("Error handling transient failure: {}", ex.getMessage(), ex);
            }

            ack.acknowledge();

        } catch (PermanentSendException e) {
            // Permanent failure - send to DLQ
            log.error("Permanent failure sending notification uuid={}: {}",
                message.getNotificationUuid(), e.getMessage());

            try {
                Optional<Notification> notificationOpt = notificationRepository.findByUuid(message.getNotificationUuid());
                if (notificationOpt.isPresent()) {
                    Notification notification = notificationOpt.get();
                    handlePermanentFailure(notification, message, e.getMessage());
                }
            } catch (Exception ex) {
                log.error("Error handling permanent failure: {}", ex.getMessage(), ex);
            }

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Unexpected error processing notification uuid={}: {}",
                message.getNotificationUuid(), e.getMessage(), e);

            // Treat unexpected errors as transient
            try {
                Optional<Notification> notificationOpt = notificationRepository.findByUuid(message.getNotificationUuid());
                if (notificationOpt.isPresent()) {
                    Notification notification = notificationOpt.get();
                    handleTransientFailure(notification, message, "Unexpected error: " + e.getMessage());
                }
            } catch (Exception ex) {
                log.error("Error handling unexpected failure: {}", ex.getMessage(), ex);
            }

            ack.acknowledge();
        }
    }

    private void handleSuccess(Notification notification, SendResult result) {
        notification.setStatus(NotificationStatus.SENT);
        notification.setSentAt(result.getSentAt());
        notification.setProviderId(result.getProviderId());
        notification.setProviderName(notification.getType().toString());
        notificationRepository.save(notification);

        // Publish sent event
        eventProducer.publishSent(
            notification.getUuid(),
            notification.getOrganizationId(),
            result.getProviderId(),
            notification.getType().toString()
        );

        log.info("Notification sent successfully: uuid={}, providerId={}",
            notification.getUuid(), result.getProviderId());
    }

    private void handleTransientFailure(Notification notification, NotificationMessage message, String errorMessage) {
        int currentRetryCount = notification.getRetryCount() != null ? notification.getRetryCount() : 0;
        int maxRetries = notification.getMaxRetries() != null ? notification.getMaxRetries() : 5;

        if (currentRetryCount >= maxRetries) {
            // Max retries exceeded
            log.error("Max retries exceeded for notification uuid={}, marking as FAILED", notification.getUuid());
            handlePermanentFailure(notification, message, "Max retries exceeded: " + errorMessage);
            return;
        }

        // Increment retry count
        notification.setRetryCount(currentRetryCount + 1);
        notification.setStatus(NotificationStatus.RETRYING);
        notification.setErrorMessage(errorMessage);
        notificationRepository.save(notification);

        // Calculate next retry delay
        long delayMs = calculateRetryDelay(notification.getRetryCount());
        long nextRetryTimestamp = System.currentTimeMillis() + delayMs;

        // Update message with new retry count
        message.setRetryCount(notification.getRetryCount());

        // Publish to retry topic
        notificationProducer.publishToRetry(message, nextRetryTimestamp);

        // Publish retried event
        eventProducer.publishRetried(
            notification.getUuid(),
            notification.getOrganizationId(),
            notification.getRetryCount(),
            nextRetryTimestamp
        );

        log.info("Scheduled retry for notification uuid={}, retryCount={}, delayMs={}",
            notification.getUuid(), notification.getRetryCount(), delayMs);
    }

    private void handlePermanentFailure(Notification notification, NotificationMessage message, String errorMessage) {
        notification.setStatus(NotificationStatus.FAILED);
        notification.setErrorMessage(errorMessage);
        notification.setErrorCode("PERMANENT_FAILURE");
        notificationRepository.save(notification);

        // Publish to DLQ
        notificationProducer.publishToDlq(message, errorMessage);

        // Publish failed event
        eventProducer.publishFailed(
            notification.getUuid(),
            notification.getOrganizationId(),
            "PERMANENT_FAILURE",
            errorMessage
        );

        log.error("Notification failed permanently: uuid={}, error={}",
            notification.getUuid(), errorMessage);
    }

    private long calculateRetryDelay(int retryCount) {
        return switch (retryCount) {
            case 1 -> retryDelay1;  // 5 seconds
            case 2 -> retryDelay2;  // 30 seconds
            case 3 -> retryDelay3;  // 2 minutes
            case 4 -> retryDelay4;  // 10 minutes
            case 5 -> retryDelay5;  // 30 minutes
            default -> retryDelay1;
        };
    }
}
