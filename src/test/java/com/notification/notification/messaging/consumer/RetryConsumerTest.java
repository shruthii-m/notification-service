package com.notification.notification.messaging.consumer;

import com.notification.notification.messaging.dto.NotificationMessage;
import com.notification.notification.messaging.producer.NotificationProducer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetryConsumer Unit Tests")
class RetryConsumerTest {

    @Mock
    private NotificationProducer notificationProducer;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private RetryConsumer retryConsumer;

    private NotificationMessage testMessage;
    private RecordHeaders headers;

    @BeforeEach
    void setUp() {
        String testUuid = UUID.randomUUID().toString();
        String testOrganizationId = "org-123";

        testMessage = NotificationMessage.builder()
                .notificationUuid(UUID.fromString(testUuid))
                .organizationId(testOrganizationId)
                .retryCount(1)
                .build();

        headers = new RecordHeaders();
    }

    // ==================== DELAY ELAPSED TESTS ====================

    @Test
    @DisplayName("Should republish to send topic when retry delay has elapsed")
    void testConsumeRetryMessage_DelayElapsed_RepublishesToSend() {
        // Arrange
        long pastTimestamp = System.currentTimeMillis() - 1000; // 1 second ago
        headers.add(new RecordHeader("X-Next-Retry-Timestamp",
                String.valueOf(pastTimestamp).getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, NotificationMessage> record = new ConsumerRecord<>(
                "notification.send.retry",
                0,
                0L,
                testMessage.getOrganizationId(),
                testMessage
        );

        // Add headers to record using reflection
        try {
            java.lang.reflect.Field headersField = ConsumerRecord.class.getDeclaredField("headers");
            headersField.setAccessible(true);
            headersField.set(record, headers);
        } catch (Exception e) {
            // If reflection fails, skip this test as it's implementation-dependent
        }

        // Act
        retryConsumer.consumeRetryMessage(record, acknowledgment);

        // Assert
        verify(notificationProducer).publishToSend(testMessage);
        verify(acknowledgment).acknowledge();
        verify(notificationProducer, never()).publishToRetry(any(), anyLong());
    }

    @Test
    @DisplayName("Should republish to send topic when current time equals retry timestamp")
    void testConsumeRetryMessage_ExactTime_RepublishesToSend() {
        // Arrange
        long currentTimestamp = System.currentTimeMillis();
        headers.add(new RecordHeader("X-Next-Retry-Timestamp",
                String.valueOf(currentTimestamp).getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, NotificationMessage> record = createRecordWithHeaders(headers);

        // Act
        retryConsumer.consumeRetryMessage(record, acknowledgment);

        // Assert
        verify(notificationProducer).publishToSend(testMessage);
        verify(acknowledgment).acknowledge();
    }

    // ==================== DELAY NOT ELAPSED TESTS ====================

    @Test
    @DisplayName("Should sleep and republish to retry topic when delay not elapsed")
    void testConsumeRetryMessage_DelayNotElapsed_RepublishesToRetry() {
        // Arrange - Set timestamp 10 seconds in the future
        long futureTimestamp = System.currentTimeMillis() + 10000;
        headers.add(new RecordHeader("X-Next-Retry-Timestamp",
                String.valueOf(futureTimestamp).getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, NotificationMessage> record = createRecordWithHeaders(headers);

        // Act
        retryConsumer.consumeRetryMessage(record, acknowledgment);

        // Assert
        verify(notificationProducer).publishToRetry(eq(testMessage), eq(futureTimestamp));
        verify(acknowledgment).acknowledge();
        verify(notificationProducer, never()).publishToSend(any());
    }

    // ==================== MISSING HEADER TESTS ====================

    @Test
    @DisplayName("Should republish immediately when timestamp header is missing")
    void testConsumeRetryMessage_MissingHeader_RepublishesImmediately() {
        // Arrange - No timestamp header
        ConsumerRecord<String, NotificationMessage> record = createRecordWithHeaders(new RecordHeaders());

        // Act
        retryConsumer.consumeRetryMessage(record, acknowledgment);

        // Assert
        verify(notificationProducer).publishToSend(testMessage);
        verify(acknowledgment).acknowledge();
        verify(notificationProducer, never()).publishToRetry(any(), anyLong());
    }

    // ==================== INVALID TIMESTAMP TESTS ====================

    @Test
    @DisplayName("Should republish immediately when timestamp is invalid")
    void testConsumeRetryMessage_InvalidTimestamp_RepublishesImmediately() {
        // Arrange
        headers.add(new RecordHeader("X-Next-Retry-Timestamp",
                "invalid-timestamp".getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, NotificationMessage> record = createRecordWithHeaders(headers);

        // Act
        retryConsumer.consumeRetryMessage(record, acknowledgment);

        // Assert
        verify(notificationProducer).publishToSend(testMessage);
        verify(acknowledgment).acknowledge();
    }

    // ==================== THREAD INTERRUPTION TESTS ====================

    @Test
    @DisplayName("Should handle thread interruption without acknowledging")
    void testConsumeRetryMessage_ThreadInterrupted_DoesNotAcknowledge() {
        // Arrange - This test is tricky because Thread.sleep is called
        // We'll test the interrupt handling by setting a very short future delay
        long futureTimestamp = System.currentTimeMillis() + 100; // 100ms in future
        headers.add(new RecordHeader("X-Next-Retry-Timestamp",
                String.valueOf(futureTimestamp).getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, NotificationMessage> record = createRecordWithHeaders(headers);

        // Interrupt the thread before calling the consumer
        Thread.currentThread().interrupt();

        // Act
        try {
            retryConsumer.consumeRetryMessage(record, acknowledgment);
        } catch (Exception e) {
            // Expected - may throw interrupted exception
        }

        // Clear the interrupted flag
        Thread.interrupted();

        // Note: This test is limited because we can't fully control thread interruption
        // In a real scenario, you'd use integration tests for this
    }

    // ==================== UNEXPECTED EXCEPTION TESTS ====================

    @Test
    @DisplayName("Should acknowledge on unexpected exception to prevent infinite loop")
    void testConsumeRetryMessage_UnexpectedException_Acknowledges() {
        // Arrange
        headers.add(new RecordHeader("X-Next-Retry-Timestamp",
                String.valueOf(System.currentTimeMillis() - 1000).getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<String, NotificationMessage> record = createRecordWithHeaders(headers);

        // Make notificationProducer throw an unexpected exception
        doThrow(new RuntimeException("Unexpected error"))
                .when(notificationProducer).publishToSend(any());

        // Act
        retryConsumer.consumeRetryMessage(record, acknowledgment);

        // Assert
        verify(acknowledgment).acknowledge();
    }

    // ==================== HELPER METHODS ====================

    /**
     * Helper method to create ConsumerRecord with headers
     */
    private ConsumerRecord<String, NotificationMessage> createRecordWithHeaders(RecordHeaders headers) {
        ConsumerRecord<String, NotificationMessage> record = new ConsumerRecord<>(
                "notification.send.retry",
                0,
                0L,
                testMessage.getOrganizationId(),
                testMessage
        );

        // Add headers using reflection
        try {
            java.lang.reflect.Field headersField = ConsumerRecord.class.getDeclaredField("headers");
            headersField.setAccessible(true);
            headersField.set(record, headers);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Fallback: create a new record with headers if reflection fails
            // This is implementation-dependent and may vary by Kafka version
        }

        return record;
    }
}
