package com.notification.notification.messaging.producer;

import com.notification.notification.messaging.dto.NotificationEvent;
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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventProducer Unit Tests")
class EventProducerTest {

    @Mock
    private KafkaTemplate<String, NotificationEvent> kafkaTemplate;

    @InjectMocks
    private EventProducer eventProducer;

    private UUID testUuid;
    private String testOrganizationId;

    @BeforeEach
    void setUp() {
        // Set topic name using reflection
        ReflectionTestUtils.setField(eventProducer, "notificationEventsTopic", "notification.events");

        // Create test data
        testUuid = UUID.randomUUID();
        testOrganizationId = "org-123";

        // Mock KafkaTemplate to return completed future (lenient for tests that override)
        CompletableFuture<SendResult<String, NotificationEvent>> future = CompletableFuture.completedFuture(null);
        lenient().when(kafkaTemplate.send(anyString(), anyString(), any(NotificationEvent.class))).thenReturn(future);
    }

    // ==================== CREATED EVENT TESTS ====================

    @Test
    @DisplayName("Should publish CREATED event with correct fields")
    void testPublishCreated_CreatesEventWithCorrectType() {
        // Act
        eventProducer.publishCreated(testUuid, testOrganizationId);

        // Assert
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(kafkaTemplate).send(eq("notification.events"), eq(testOrganizationId), eventCaptor.capture());

        NotificationEvent event = eventCaptor.getValue();
        assertThat(event.getNotificationUuid()).isEqualTo(testUuid);
        assertThat(event.getOrganizationId()).isEqualTo(testOrganizationId);
        assertThat(event.getEventType()).isEqualTo(NotificationEvent.EventType.CREATED);
        assertThat(event.getDetails()).isNotNull();
        assertThat(event.getDetails()).isEmpty();
    }

    // ==================== SEND ATTEMPTED EVENT TESTS ====================

    @Test
    @DisplayName("Should publish SEND_ATTEMPTED event with retry count")
    void testPublishSendAttempted_IncludesRetryCount() {
        // Arrange
        int retryCount = 2;

        // Act
        eventProducer.publishSendAttempted(testUuid, testOrganizationId, retryCount);

        // Assert
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(kafkaTemplate).send(eq("notification.events"), eq(testOrganizationId), eventCaptor.capture());

        NotificationEvent event = eventCaptor.getValue();
        assertThat(event.getNotificationUuid()).isEqualTo(testUuid);
        assertThat(event.getOrganizationId()).isEqualTo(testOrganizationId);
        assertThat(event.getEventType()).isEqualTo(NotificationEvent.EventType.SEND_ATTEMPTED);
        assertThat(event.getDetails()).containsEntry("retryCount", retryCount);
    }

    @Test
    @DisplayName("Should publish SEND_ATTEMPTED event with retry count zero on first attempt")
    void testPublishSendAttempted_FirstAttempt() {
        // Act
        eventProducer.publishSendAttempted(testUuid, testOrganizationId, 0);

        // Assert
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(kafkaTemplate).send(anyString(), anyString(), eventCaptor.capture());

        NotificationEvent event = eventCaptor.getValue();
        assertThat(event.getDetails()).containsEntry("retryCount", 0);
    }

    // ==================== SENT EVENT TESTS ====================

    @Test
    @DisplayName("Should publish SENT event with provider details")
    void testPublishSent_IncludesProviderDetails() {
        // Arrange
        String providerId = "provider-123";
        String providerName = "EmailProvider";

        // Act
        eventProducer.publishSent(testUuid, testOrganizationId, providerId, providerName);

        // Assert
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(kafkaTemplate).send(eq("notification.events"), eq(testOrganizationId), eventCaptor.capture());

        NotificationEvent event = eventCaptor.getValue();
        assertThat(event.getNotificationUuid()).isEqualTo(testUuid);
        assertThat(event.getOrganizationId()).isEqualTo(testOrganizationId);
        assertThat(event.getEventType()).isEqualTo(NotificationEvent.EventType.SENT);
        assertThat(event.getDetails()).containsEntry("providerId", providerId);
        assertThat(event.getDetails()).containsEntry("providerName", providerName);
    }

    // ==================== FAILED EVENT TESTS ====================

    @Test
    @DisplayName("Should publish FAILED event with error details")
    void testPublishFailed_IncludesErrorDetails() {
        // Arrange
        String errorCode = "INVALID_RECIPIENT";
        String errorMessage = "Invalid email address";

        // Act
        eventProducer.publishFailed(testUuid, testOrganizationId, errorCode, errorMessage);

        // Assert
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(kafkaTemplate).send(eq("notification.events"), eq(testOrganizationId), eventCaptor.capture());

        NotificationEvent event = eventCaptor.getValue();
        assertThat(event.getNotificationUuid()).isEqualTo(testUuid);
        assertThat(event.getOrganizationId()).isEqualTo(testOrganizationId);
        assertThat(event.getEventType()).isEqualTo(NotificationEvent.EventType.FAILED);
        assertThat(event.getDetails()).containsEntry("errorCode", errorCode);
        assertThat(event.getDetails()).containsEntry("errorMessage", errorMessage);
    }

    // ==================== RETRIED EVENT TESTS ====================

    @Test
    @DisplayName("Should publish RETRIED event with retry details")
    void testPublishRetried_IncludesRetryDetails() {
        // Arrange
        int retryCount = 3;
        long nextRetryTimestamp = System.currentTimeMillis() + 10000;

        // Act
        eventProducer.publishRetried(testUuid, testOrganizationId, retryCount, nextRetryTimestamp);

        // Assert
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(kafkaTemplate).send(eq("notification.events"), eq(testOrganizationId), eventCaptor.capture());

        NotificationEvent event = eventCaptor.getValue();
        assertThat(event.getNotificationUuid()).isEqualTo(testUuid);
        assertThat(event.getOrganizationId()).isEqualTo(testOrganizationId);
        assertThat(event.getEventType()).isEqualTo(NotificationEvent.EventType.RETRIED);
        assertThat(event.getDetails()).containsEntry("retryCount", retryCount);
        assertThat(event.getDetails()).containsEntry("nextRetryTimestamp", nextRetryTimestamp);
    }

    // ==================== PARTITION KEY TESTS ====================

    @Test
    @DisplayName("Should use organizationId as partition key")
    void testPublishEvent_UsesOrganizationIdAsKey() {
        // Act
        eventProducer.publishCreated(testUuid, testOrganizationId);

        // Assert
        verify(kafkaTemplate).send(anyString(), eq(testOrganizationId), any(NotificationEvent.class));
    }

    @Test
    @DisplayName("Should use 'default' key when organizationId is null")
    void testPublishEvent_NullOrganizationId_UsesDefault() {
        // Act
        eventProducer.publishCreated(testUuid, null);

        // Assert
        verify(kafkaTemplate).send(anyString(), eq("default"), any(NotificationEvent.class));
    }

    // ==================== ASYNC HANDLING TESTS ====================

    @Test
    @DisplayName("Should handle successful async publish")
    void testPublishEvent_Success() {
        // Arrange
        SendResult<String, NotificationEvent> sendResult = mock(SendResult.class);
        org.apache.kafka.clients.producer.RecordMetadata recordMetadata =
            mock(org.apache.kafka.clients.producer.RecordMetadata.class);
        when(sendResult.getRecordMetadata()).thenReturn(recordMetadata);

        CompletableFuture<SendResult<String, NotificationEvent>> future =
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(anyString(), anyString(), any(NotificationEvent.class))).thenReturn(future);

        // Act
        eventProducer.publishCreated(testUuid, testOrganizationId);

        // Assert
        verify(kafkaTemplate).send(anyString(), anyString(), any(NotificationEvent.class));
        // No exception should be thrown
    }

    @Test
    @DisplayName("Should handle failed async publish")
    void testPublishEvent_Failure() {
        // Arrange
        CompletableFuture<SendResult<String, NotificationEvent>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka connection error"));
        when(kafkaTemplate.send(anyString(), anyString(), any(NotificationEvent.class))).thenReturn(future);

        // Act
        eventProducer.publishCreated(testUuid, testOrganizationId);

        // Assert
        verify(kafkaTemplate).send(anyString(), anyString(), any(NotificationEvent.class));
        // Should log error but not throw exception (async)
    }
}
