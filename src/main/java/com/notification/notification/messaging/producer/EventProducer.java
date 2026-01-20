package com.notification.notification.messaging.producer;

import com.notification.notification.messaging.dto.NotificationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class EventProducer {

    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;

    @Value("${kafka.topic.notification-events}")
    private String notificationEventsTopic;

    public EventProducer(KafkaTemplate<String, NotificationEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishEvent(NotificationEvent event) {
        String key = event.getOrganizationId() != null ? event.getOrganizationId() : "default";

        kafkaTemplate.send(notificationEventsTopic, key, event)
            .whenComplete((result, ex) -> {
                if (ex == null) {
                    log.debug("Event published: type={}, notificationUuid={}, partition={}, offset={}",
                        event.getEventType(),
                        event.getNotificationUuid(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                } else {
                    log.error("Failed to publish event: type={}, notificationUuid={}, error={}",
                        event.getEventType(), event.getNotificationUuid(), ex.getMessage());
                }
            });
    }

    public void publishCreated(UUID notificationUuid, String organizationId) {
        NotificationEvent event = NotificationEvent.builder()
            .notificationUuid(notificationUuid)
            .organizationId(organizationId)
            .eventType(NotificationEvent.EventType.CREATED)
            .details(new HashMap<>())
            .build();

        publishEvent(event);
    }

    public void publishSendAttempted(UUID notificationUuid, String organizationId, int retryCount) {
        Map<String, Object> details = new HashMap<>();
        details.put("retryCount", retryCount);

        NotificationEvent event = NotificationEvent.builder()
            .notificationUuid(notificationUuid)
            .organizationId(organizationId)
            .eventType(NotificationEvent.EventType.SEND_ATTEMPTED)
            .details(details)
            .build();

        publishEvent(event);
    }

    public void publishSent(UUID notificationUuid, String organizationId, String providerId, String providerName) {
        Map<String, Object> details = new HashMap<>();
        details.put("providerId", providerId);
        details.put("providerName", providerName);

        NotificationEvent event = NotificationEvent.builder()
            .notificationUuid(notificationUuid)
            .organizationId(organizationId)
            .eventType(NotificationEvent.EventType.SENT)
            .details(details)
            .build();

        publishEvent(event);
    }

    public void publishFailed(UUID notificationUuid, String organizationId, String errorCode, String errorMessage) {
        Map<String, Object> details = new HashMap<>();
        details.put("errorCode", errorCode);
        details.put("errorMessage", errorMessage);

        NotificationEvent event = NotificationEvent.builder()
            .notificationUuid(notificationUuid)
            .organizationId(organizationId)
            .eventType(NotificationEvent.EventType.FAILED)
            .details(details)
            .build();

        publishEvent(event);
    }

    public void publishRetried(UUID notificationUuid, String organizationId, int retryCount, long nextRetryTimestamp) {
        Map<String, Object> details = new HashMap<>();
        details.put("retryCount", retryCount);
        details.put("nextRetryTimestamp", nextRetryTimestamp);

        NotificationEvent event = NotificationEvent.builder()
            .notificationUuid(notificationUuid)
            .organizationId(organizationId)
            .eventType(NotificationEvent.EventType.RETRIED)
            .details(details)
            .build();

        publishEvent(event);
    }
}
