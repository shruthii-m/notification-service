package com.notification.notification.messaging.producer;

import com.notification.notification.messaging.dto.NotificationMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class NotificationProducer {

    private final KafkaTemplate<String, NotificationMessage> kafkaTemplate;

    @Value("${kafka.topic.notification-requested}")
    private String notificationRequestedTopic;

    @Value("${kafka.topic.notification-send}")
    private String notificationSendTopic;

    @Value("${kafka.topic.notification-send-retry}")
    private String notificationSendRetryTopic;

    @Value("${kafka.topic.notification-send-dlq}")
    private String notificationSendDlqTopic;

    public NotificationProducer(KafkaTemplate<String, NotificationMessage> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishToRequested(NotificationMessage message) {
        String key = message.getNotificationUuid().toString();
        ProducerRecord<String, NotificationMessage> record = new ProducerRecord<>(
            notificationRequestedTopic,
            key,
            message
        );

        addCommonHeaders(record, message);
        send(record, notificationRequestedTopic);
    }

    public void publishToSend(NotificationMessage message) {
        String key = message.getOrganizationId() != null ? message.getOrganizationId() : "default";
        ProducerRecord<String, NotificationMessage> record = new ProducerRecord<>(
            notificationSendTopic,
            key,
            message
        );

        addCommonHeaders(record, message);
        send(record, notificationSendTopic);
    }

    public void publishToRetry(NotificationMessage message, long nextRetryTimestamp) {
        String key = message.getOrganizationId() != null ? message.getOrganizationId() : "default";
        ProducerRecord<String, NotificationMessage> record = new ProducerRecord<>(
            notificationSendRetryTopic,
            key,
            message
        );

        addCommonHeaders(record, message);
        record.headers().add("X-Next-Retry-Timestamp", String.valueOf(nextRetryTimestamp).getBytes());
        record.headers().add("X-Retry-Count", String.valueOf(message.getRetryCount()).getBytes());

        send(record, notificationSendRetryTopic);
    }

    public void publishToDlq(NotificationMessage message, String reason) {
        String key = message.getNotificationUuid().toString();
        ProducerRecord<String, NotificationMessage> record = new ProducerRecord<>(
            notificationSendDlqTopic,
            key,
            message
        );

        addCommonHeaders(record, message);
        record.headers().add("X-DLQ-Reason", reason.getBytes());

        send(record, notificationSendDlqTopic);
        log.error("Notification sent to DLQ: uuid={}, reason={}", message.getNotificationUuid(), reason);
    }

    private void addCommonHeaders(ProducerRecord<String, NotificationMessage> record, NotificationMessage message) {
        String correlationId = message.getCorrelationId() != null
            ? message.getCorrelationId()
            : UUID.randomUUID().toString();

        record.headers().add("X-Correlation-Id", correlationId.getBytes());

        if (message.getOrganizationId() != null) {
            record.headers().add("X-Organization-Id", message.getOrganizationId().getBytes());
        }
    }

    private void send(ProducerRecord<String, NotificationMessage> record, String topicName) {
        CompletableFuture<SendResult<String, NotificationMessage>> future = kafkaTemplate.send(record);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Message sent to topic={}, partition={}, offset={}, key={}",
                    topicName,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(),
                    record.key());
            } else {
                log.error("Failed to send message to topic={}, key={}, error={}",
                    topicName, record.key(), ex.getMessage());
            }
        });
    }
}
