package com.notification.notification.messaging.producer;

import com.notification.notification.messaging.dto.NotificationMessage;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationProducer Unit Tests")
class NotificationProducerTest {

    @Mock
    private KafkaTemplate<String, NotificationMessage> kafkaTemplate;

    @InjectMocks
    private NotificationProducer notificationProducer;

    private NotificationMessage testMessage;
    private String testUuid;
    private String testOrganizationId;
    private String testCorrelationId;

    @BeforeEach
    void setUp() {
        // Set topic names using reflection
        ReflectionTestUtils.setField(notificationProducer, "notificationRequestedTopic", "notification.requested");
        ReflectionTestUtils.setField(notificationProducer, "notificationSendTopic", "notification.send");
        ReflectionTestUtils.setField(notificationProducer, "notificationSendRetryTopic", "notification.send.retry");
        ReflectionTestUtils.setField(notificationProducer, "notificationSendDlqTopic", "notification.send.dlq");

        // Create test data
        testUuid = UUID.randomUUID().toString();
        testOrganizationId = "org-123";
        testCorrelationId = UUID.randomUUID().toString();

        testMessage = NotificationMessage.builder()
                .notificationUuid(UUID.fromString(testUuid))
                .organizationId(testOrganizationId)
                .correlationId(testCorrelationId)
                .retryCount(0)
                .build();

        // Mock KafkaTemplate to return completed future (lenient for tests that override)
        CompletableFuture<SendResult<String, NotificationMessage>> future = CompletableFuture.completedFuture(null);
        lenient().when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
    }

    // ==================== PUBLISH TO SEND TESTS ====================

    @Test
    @DisplayName("Should publish to send topic with organizationId as key")
    void testPublishToSend_UsesOrganizationIdAsKey() {
        // Act
        notificationProducer.publishToSend(testMessage);

        // Assert
        ArgumentCaptor<ProducerRecord<String, NotificationMessage>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, NotificationMessage> record = recordCaptor.getValue();
        assertThat(record.topic()).isEqualTo("notification.send");
        assertThat(record.key()).isEqualTo(testOrganizationId);
        assertThat(record.value()).isEqualTo(testMessage);
    }

    @Test
    @DisplayName("Should use 'default' key when organizationId is null")
    void testPublishToSend_NullOrganizationId_UsesDefault() {
        // Arrange
        testMessage.setOrganizationId(null);

        // Act
        notificationProducer.publishToSend(testMessage);

        // Assert
        ArgumentCaptor<ProducerRecord<String, NotificationMessage>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, NotificationMessage> record = recordCaptor.getValue();
        assertThat(record.key()).isEqualTo("default");
    }

    @Test
    @DisplayName("Should add correlation and organization headers to send topic")
    void testPublishToSend_AddsCommonHeaders() {
        // Act
        notificationProducer.publishToSend(testMessage);

        // Assert
        ArgumentCaptor<ProducerRecord<String, NotificationMessage>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, NotificationMessage> record = recordCaptor.getValue();

        Header correlationHeader = record.headers().lastHeader("X-Correlation-Id");
        assertThat(correlationHeader).isNotNull();
        assertThat(new String(correlationHeader.value())).isEqualTo(testCorrelationId);

        Header orgHeader = record.headers().lastHeader("X-Organization-Id");
        assertThat(orgHeader).isNotNull();
        assertThat(new String(orgHeader.value())).isEqualTo(testOrganizationId);
    }

    // ==================== PUBLISH TO RETRY TESTS ====================

    @Test
    @DisplayName("Should publish to retry topic with retry headers")
    void testPublishToRetry_AddsRetryHeaders() {
        // Arrange
        long nextRetryTimestamp = System.currentTimeMillis() + 5000;
        testMessage.setRetryCount(2);

        // Act
        notificationProducer.publishToRetry(testMessage, nextRetryTimestamp);

        // Assert
        ArgumentCaptor<ProducerRecord<String, NotificationMessage>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, NotificationMessage> record = recordCaptor.getValue();
        assertThat(record.topic()).isEqualTo("notification.send.retry");
        assertThat(record.key()).isEqualTo(testOrganizationId);

        // Check retry-specific headers
        Header timestampHeader = record.headers().lastHeader("X-Next-Retry-Timestamp");
        assertThat(timestampHeader).isNotNull();
        assertThat(new String(timestampHeader.value())).isEqualTo(String.valueOf(nextRetryTimestamp));

        Header retryCountHeader = record.headers().lastHeader("X-Retry-Count");
        assertThat(retryCountHeader).isNotNull();
        assertThat(new String(retryCountHeader.value())).isEqualTo("2");
    }

    @Test
    @DisplayName("Should include common headers in retry topic")
    void testPublishToRetry_IncludesCommonHeaders() {
        // Arrange
        long nextRetryTimestamp = System.currentTimeMillis() + 5000;

        // Act
        notificationProducer.publishToRetry(testMessage, nextRetryTimestamp);

        // Assert
        ArgumentCaptor<ProducerRecord<String, NotificationMessage>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, NotificationMessage> record = recordCaptor.getValue();

        Header correlationHeader = record.headers().lastHeader("X-Correlation-Id");
        assertThat(correlationHeader).isNotNull();

        Header orgHeader = record.headers().lastHeader("X-Organization-Id");
        assertThat(orgHeader).isNotNull();
    }

    // ==================== PUBLISH TO DLQ TESTS ====================

    @Test
    @DisplayName("Should publish to DLQ with reason header")
    void testPublishToDlq_AddsDlqReasonHeader() {
        // Arrange
        String reason = "Max retries exceeded";

        // Act
        notificationProducer.publishToDlq(testMessage, reason);

        // Assert
        ArgumentCaptor<ProducerRecord<String, NotificationMessage>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, NotificationMessage> record = recordCaptor.getValue();
        assertThat(record.topic()).isEqualTo("notification.send.dlq");
        assertThat(record.key()).isEqualTo(testUuid);

        Header reasonHeader = record.headers().lastHeader("X-DLQ-Reason");
        assertThat(reasonHeader).isNotNull();
        assertThat(new String(reasonHeader.value())).isEqualTo(reason);
    }

    @Test
    @DisplayName("Should use notificationUuid as key for DLQ")
    void testPublishToDlq_UsesUuidAsKey() {
        // Act
        notificationProducer.publishToDlq(testMessage, "Permanent failure");

        // Assert
        ArgumentCaptor<ProducerRecord<String, NotificationMessage>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, NotificationMessage> record = recordCaptor.getValue();
        assertThat(record.key()).isEqualTo(testUuid);
    }

    // ==================== PUBLISH TO REQUESTED TESTS ====================

    @Test
    @DisplayName("Should publish to requested topic with uuid as key")
    void testPublishToRequested_UsesUuidAsKey() {
        // Act
        notificationProducer.publishToRequested(testMessage);

        // Assert
        ArgumentCaptor<ProducerRecord<String, NotificationMessage>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, NotificationMessage> record = recordCaptor.getValue();
        assertThat(record.topic()).isEqualTo("notification.requested");
        assertThat(record.key()).isEqualTo(testUuid);
        assertThat(record.value()).isEqualTo(testMessage);
    }

    // ==================== COMMON HEADERS TESTS ====================

    @Test
    @DisplayName("Should generate correlation ID if not present in message")
    void testAddCommonHeaders_GeneratesCorrelationId() {
        // Arrange
        testMessage.setCorrelationId(null);

        // Act
        notificationProducer.publishToSend(testMessage);

        // Assert
        ArgumentCaptor<ProducerRecord<String, NotificationMessage>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, NotificationMessage> record = recordCaptor.getValue();
        Header correlationHeader = record.headers().lastHeader("X-Correlation-Id");

        assertThat(correlationHeader).isNotNull();
        String generatedCorrelationId = new String(correlationHeader.value());
        assertThat(generatedCorrelationId).isNotEmpty();
        // Verify it's a valid UUID format
        assertThat(generatedCorrelationId).matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
    }

    @Test
    @DisplayName("Should not add organization header if organizationId is null")
    void testAddCommonHeaders_NullOrganizationId_NoHeader() {
        // Arrange
        testMessage.setOrganizationId(null);

        // Act
        notificationProducer.publishToRequested(testMessage);

        // Assert
        ArgumentCaptor<ProducerRecord<String, NotificationMessage>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, NotificationMessage> record = recordCaptor.getValue();
        Header orgHeader = record.headers().lastHeader("X-Organization-Id");

        assertThat(orgHeader).isNull();
    }

    // ==================== ASYNC SEND TESTS ====================

    @Test
    @DisplayName("Should handle successful async send")
    void testSend_Success() {
        // Arrange
        SendResult<String, NotificationMessage> sendResult = mock(SendResult.class);
        org.apache.kafka.clients.producer.RecordMetadata recordMetadata =
            mock(org.apache.kafka.clients.producer.RecordMetadata.class);
        when(sendResult.getRecordMetadata()).thenReturn(recordMetadata);

        CompletableFuture<SendResult<String, NotificationMessage>> future =
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        // Act
        notificationProducer.publishToSend(testMessage);

        // Assert
        verify(kafkaTemplate).send(any(ProducerRecord.class));
        // No exception should be thrown
    }

    @Test
    @DisplayName("Should handle failed async send")
    void testSend_Failure() {
        // Arrange
        CompletableFuture<SendResult<String, NotificationMessage>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka connection error"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        // Act
        notificationProducer.publishToSend(testMessage);

        // Assert
        verify(kafkaTemplate).send(any(ProducerRecord.class));
        // Should log error but not throw exception (async)
    }
}
