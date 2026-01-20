package com.notification.notification.messaging.consumer;

import com.notification.notification.messaging.dto.NotificationMessage;
import com.notification.notification.messaging.producer.NotificationProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryConsumer {

    private final NotificationProducer notificationProducer;

    @KafkaListener(
        topics = "${kafka.topic.notification-send-retry}",
        groupId = "notification-retry-group"
    )
    public void consumeRetryMessage(
        ConsumerRecord<String, NotificationMessage> record,
        Acknowledgment ack
    ) {
        NotificationMessage message = record.value();
        log.info("Received retry message: uuid={}, retryCount={}",
            message.getNotificationUuid(), message.getRetryCount());

        try {
            // Extract nextRetryTimestamp from headers
            Header nextRetryHeader = record.headers().lastHeader("X-Next-Retry-Timestamp");

            if (nextRetryHeader == null) {
                log.warn("No X-Next-Retry-Timestamp header found, republishing immediately for uuid={}",
                    message.getNotificationUuid());
                notificationProducer.publishToSend(message);
                ack.acknowledge();
                return;
            }

            String nextRetryTimestampStr = new String(nextRetryHeader.value(), StandardCharsets.UTF_8);
            long nextRetryTimestamp = Long.parseLong(nextRetryTimestampStr);
            long currentTime = System.currentTimeMillis();

            // Check if it's time to retry
            if (currentTime >= nextRetryTimestamp) {
                // Time to retry - republish to send topic
                log.info("Retry delay elapsed, republishing to send topic: uuid={}, retryCount={}",
                    message.getNotificationUuid(), message.getRetryCount());
                notificationProducer.publishToSend(message);
                ack.acknowledge();
            } else {
                // Not ready yet - pause and reprocess later
                long remainingDelay = nextRetryTimestamp - currentTime;
                log.info("Retry not yet due, sleeping for {}ms: uuid={}",
                    remainingDelay, message.getNotificationUuid());

                // Sleep and then republish back to retry topic
                Thread.sleep(Math.min(remainingDelay, 5000)); // Sleep max 5 seconds at a time

                // Republish to retry topic to check again later
                notificationProducer.publishToRetry(message, nextRetryTimestamp);
                ack.acknowledge();
            }

        } catch (NumberFormatException e) {
            log.error("Invalid retry timestamp for uuid={}: {}",
                message.getNotificationUuid(), e.getMessage());
            // Republish to send topic immediately if timestamp is invalid
            notificationProducer.publishToSend(message);
            ack.acknowledge();

        } catch (InterruptedException e) {
            log.warn("Retry consumer interrupted for uuid={}", message.getNotificationUuid());
            Thread.currentThread().interrupt();
            // Don't acknowledge - message will be reprocessed

        } catch (Exception e) {
            log.error("Error processing retry message for uuid={}: {}",
                message.getNotificationUuid(), e.getMessage(), e);
            // Acknowledge to prevent infinite reprocessing
            ack.acknowledge();
        }
    }
}
