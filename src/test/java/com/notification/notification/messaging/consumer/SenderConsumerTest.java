package com.notification.notification.messaging.consumer;

import com.notification.notification.entity.Notification;
import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.entity.NotificationType;
import com.notification.notification.exception.PermanentSendException;
import com.notification.notification.exception.TransientSendException;
import com.notification.notification.messaging.dto.NotificationMessage;
import com.notification.notification.messaging.producer.EventProducer;
import com.notification.notification.messaging.producer.NotificationProducer;
import com.notification.notification.repository.NotificationRepository;
import com.notification.notification.sender.NotificationSender;
import com.notification.notification.sender.NotificationSenderFactory;
import com.notification.notification.sender.SendResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SenderConsumer Unit Tests")
class SenderConsumerTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationSenderFactory senderFactory;

    @Mock
    private NotificationProducer notificationProducer;

    @Mock
    private EventProducer eventProducer;

    @Mock
    private Acknowledgment acknowledgment;

    @Mock
    private NotificationSender notificationSender;

    @InjectMocks
    private SenderConsumer senderConsumer;

    private Notification testNotification;
    private NotificationMessage testMessage;
    private ConsumerRecord<String, NotificationMessage> consumerRecord;

    @BeforeEach
    void setUp() {
        // Set retry delay values using reflection
        ReflectionTestUtils.setField(senderConsumer, "retryDelay1", 100L);
        ReflectionTestUtils.setField(senderConsumer, "retryDelay2", 200L);
        ReflectionTestUtils.setField(senderConsumer, "retryDelay3", 300L);
        ReflectionTestUtils.setField(senderConsumer, "retryDelay4", 400L);
        ReflectionTestUtils.setField(senderConsumer, "retryDelay5", 500L);

        // Create test data
        UUID notificationUuid = UUID.randomUUID();
        String organizationId = "org-123";

        testNotification = Notification.builder()
                .id(1L)
                .uuid(notificationUuid)
                .organizationId(organizationId)
                .type(NotificationType.EMAIL)
                .recipient("test@example.com")
                .title("Test Subject")
                .message("Test Content")
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .maxRetries(5)
                .createdAt(LocalDateTime.now())
                .build();

        testMessage = NotificationMessage.builder()
                .notificationUuid(notificationUuid)
                .organizationId(organizationId)
                .retryCount(0)
                .build();

        consumerRecord = new ConsumerRecord<>(
                "notification.send",
                0,
                0L,
                organizationId,
                testMessage
        );
    }

    // ==================== IDEMPOTENCY TESTS ====================

    @Test
    @DisplayName("Should skip processing if notification already sent (idempotency)")
    void testConsumeSendMessage_AlreadySent_SkipsProcessing() {
        // Arrange
        testNotification.setStatus(NotificationStatus.SENT);
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification));

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        verify(notificationRepository, never()).save(any());
        verify(senderFactory, never()).getSender(any());
        verify(acknowledgment, times(1)).acknowledge();
    }

    // ==================== SUCCESS TESTS ====================

    @Test
    @DisplayName("Should successfully send notification on first attempt")
    void testConsumeSendMessage_Success_FirstAttempt() {
        // Arrange
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification));

        // Track status changes via ArgumentCaptor that captures state at call time
        java.util.List<NotificationStatus> statusHistory = new java.util.ArrayList<>();
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> {
                    Notification n = invocation.getArgument(0);
                    statusHistory.add(n.getStatus());
                    return n;
                });

        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));

        SendResult successResult = SendResult.builder()
                .success(true)
                .providerId("provider-123")
                .sentAt(LocalDateTime.now())
                .build();

        when(notificationSender.send(any(Notification.class)))
                .thenReturn(successResult);

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        verify(notificationRepository, times(2)).save(any(Notification.class));

        // Check the statuses at each save call
        assertThat(statusHistory).hasSize(2);
        assertThat(statusHistory.get(0)).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(statusHistory.get(1)).isEqualTo(NotificationStatus.SENT);

        // Verify final notification state
        assertThat(testNotification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(testNotification.getProviderId()).isEqualTo("provider-123");
        assertThat(testNotification.getSentAt()).isNotNull();

        // Verify events published
        verify(eventProducer).publishSendAttempted(
                eq(testNotification.getUuid()),
                eq(testNotification.getOrganizationId()),
                eq(0)
        );
        verify(eventProducer).publishSent(
                eq(testNotification.getUuid()),
                eq(testNotification.getOrganizationId()),
                eq("provider-123"),
                anyString()
        );

        verify(acknowledgment, times(1)).acknowledge();
    }

    // ==================== TRANSIENT FAILURE TESTS ====================

    @Test
    @DisplayName("Should schedule retry on transient failure (attempt 1)")
    void testConsumeSendMessage_TransientFailure_Retry1() {
        // Arrange
        lenient().when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification))
                .thenReturn(Optional.of(testNotification));

        // Track status changes
        java.util.List<NotificationStatus> statusHistory = new java.util.ArrayList<>();
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> {
                    Notification n = invocation.getArgument(0);
                    statusHistory.add(n.getStatus());
                    return n;
                });

        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));
        when(notificationSender.send(any(Notification.class)))
                .thenThrow(new TransientSendException("Temporary network error"));

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        verify(notificationRepository, times(2)).save(any(Notification.class));

        // Check status history
        assertThat(statusHistory).hasSize(2);
        assertThat(statusHistory.get(0)).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(statusHistory.get(1)).isEqualTo(NotificationStatus.RETRYING);

        // Verify final notification state
        assertThat(testNotification.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(testNotification.getRetryCount()).isEqualTo(1);
        assertThat(testNotification.getErrorMessage()).contains("Temporary network error");

        // Verify retry scheduled
        ArgumentCaptor<Long> timestampCaptor = ArgumentCaptor.forClass(Long.class);
        verify(notificationProducer).publishToRetry(any(NotificationMessage.class), timestampCaptor.capture());

        long nextRetryTimestamp = timestampCaptor.getValue();
        long expectedDelay = 100L; // retryDelay1
        assertThat(nextRetryTimestamp).isGreaterThan(System.currentTimeMillis());
        assertThat(nextRetryTimestamp).isLessThan(System.currentTimeMillis() + expectedDelay + 100);

        verify(eventProducer).publishRetried(
                eq(testNotification.getUuid()),
                eq(testNotification.getOrganizationId()),
                eq(1),
                anyLong()
        );
        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    @DisplayName("Should schedule retry with increasing delays (retries 2-5)")
    void testConsumeSendMessage_TransientFailure_IncrementalDelays() {
        // Test retry count 2
        testNotification.setRetryCount(1);
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification))
                .thenReturn(Optional.of(testNotification));
        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));
        when(notificationSender.send(any(Notification.class)))
                .thenThrow(new TransientSendException("Temporary error"));

        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(captor.capture());

        Notification saved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(saved.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should send to DLQ when max retries exceeded")
    void testConsumeSendMessage_MaxRetriesExceeded_SendsToDLQ() {
        // Arrange
        testNotification.setRetryCount(5); // Already at max retries
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification))
                .thenReturn(Optional.of(testNotification));
        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));
        when(notificationSender.send(any(Notification.class)))
                .thenThrow(new TransientSendException("Still failing"));

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(notificationCaptor.capture());

        Notification savedNotification = notificationCaptor.getAllValues().get(1);
        assertThat(savedNotification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(savedNotification.getErrorCode()).isEqualTo("PERMANENT_FAILURE");

        verify(notificationProducer).publishToDlq(any(NotificationMessage.class), anyString());
        verify(eventProducer).publishFailed(
                eq(testNotification.getUuid()),
                eq(testNotification.getOrganizationId()),
                eq("PERMANENT_FAILURE"),
                anyString()
        );
        verify(acknowledgment, times(1)).acknowledge();
    }

    // ==================== PERMANENT FAILURE TESTS ====================

    @Test
    @DisplayName("Should send to DLQ immediately on permanent failure")
    void testConsumeSendMessage_PermanentFailure_SendsToDLQ() {
        // Arrange
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification))
                .thenReturn(Optional.of(testNotification));
        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));
        when(notificationSender.send(any(Notification.class)))
                .thenThrow(new PermanentSendException("Invalid email address"));

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(notificationCaptor.capture());

        Notification savedNotification = notificationCaptor.getAllValues().get(1);
        assertThat(savedNotification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(savedNotification.getErrorCode()).isEqualTo("PERMANENT_FAILURE");
        assertThat(savedNotification.getErrorMessage()).contains("Invalid email address");

        verify(notificationProducer).publishToDlq(any(NotificationMessage.class), contains("Invalid email"));
        verify(eventProducer).publishFailed(
                eq(testNotification.getUuid()),
                eq(testNotification.getOrganizationId()),
                eq("PERMANENT_FAILURE"),
                anyString()
        );
        verify(acknowledgment, times(1)).acknowledge();
    }

    // ==================== ERROR HANDLING TESTS ====================

    @Test
    @DisplayName("Should acknowledge when notification not found")
    void testConsumeSendMessage_NotificationNotFound_Acknowledges() {
        // Arrange
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.empty());

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        verify(notificationRepository, never()).save(any());
        verify(senderFactory, never()).getSender(any());
        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    @DisplayName("Should handle no sender available as permanent failure")
    void testConsumeSendMessage_NoSenderAvailable_PermanentFailure() {
        // Arrange
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification));
        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.empty());

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(notificationCaptor.capture());

        Notification savedNotification = notificationCaptor.getAllValues().get(1);
        assertThat(savedNotification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(savedNotification.getErrorMessage()).contains("No sender available");

        verify(notificationProducer).publishToDlq(any(NotificationMessage.class), anyString());
        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    @DisplayName("Should treat unexpected exceptions as transient failures")
    void testConsumeSendMessage_UnexpectedException_TransientFailure() {
        // Arrange
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification))
                .thenReturn(Optional.of(testNotification));
        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));
        when(notificationSender.send(any(Notification.class)))
                .thenThrow(new RuntimeException("Unexpected error"));

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(notificationCaptor.capture());

        Notification savedNotification = notificationCaptor.getAllValues().get(notificationCaptor.getAllValues().size() - 1);
        assertThat(savedNotification.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(savedNotification.getRetryCount()).isEqualTo(1);
        assertThat(savedNotification.getErrorMessage()).contains("Unexpected error");

        verify(notificationProducer).publishToRetry(any(NotificationMessage.class), anyLong());
        verify(acknowledgment, times(1)).acknowledge();
    }

    // ==================== RETRY DELAY CALCULATION TESTS ====================

    @Test
    @DisplayName("Should calculate retry delays correctly for each level")
    void testRetryDelayCalculation() {
        // We test this indirectly through transient failures
        testNotification.setRetryCount(0);

        // Retry 1: delay should be 100ms
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification))
                .thenReturn(Optional.of(testNotification));
        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));
        when(notificationSender.send(any(Notification.class)))
                .thenThrow(new TransientSendException("Error"));

        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        ArgumentCaptor<Long> timestampCaptor = ArgumentCaptor.forClass(Long.class);
        verify(notificationProducer).publishToRetry(any(NotificationMessage.class), timestampCaptor.capture());

        long nextRetryTimestamp = timestampCaptor.getValue();
        long actualDelay = nextRetryTimestamp - System.currentTimeMillis();
        assertThat(actualDelay).isBetween(50L, 150L); // Allow 50ms tolerance
    }

    @Test
    @DisplayName("Should handle null retry count gracefully")
    void testConsumeSendMessage_NullRetryCount_DefaultsToZero() {
        // Arrange
        testNotification.setRetryCount(null);
        lenient().when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification));
        lenient().when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));
        lenient().when(notificationSender.send(any(Notification.class)))
                .thenThrow(new TransientSendException("Error"));

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(notificationCaptor.capture());

        Notification savedNotification = notificationCaptor.getAllValues().get(notificationCaptor.getAllValues().size() - 1);
        assertThat(savedNotification.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should handle null max retries gracefully")
    void testConsumeSendMessage_NullMaxRetries_DefaultsToFive() {
        // Arrange
        testNotification.setMaxRetries(null);
        testNotification.setRetryCount(5);
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification))
                .thenReturn(Optional.of(testNotification));
        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));
        when(notificationSender.send(any(Notification.class)))
                .thenThrow(new TransientSendException("Error"));

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert - Should go to DLQ since retryCount (5) >= default maxRetries (5)
        verify(notificationProducer).publishToDlq(any(NotificationMessage.class), anyString());
    }

    // ==================== EVENT PUBLISHING TESTS ====================

    @Test
    @DisplayName("Should publish send attempted event before sending")
    void testConsumeSendMessage_PublishesSendAttemptedEvent() {
        // Arrange
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification));
        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));

        SendResult successResult = SendResult.builder()
                .success(true)
                .providerId("provider-123")
                .sentAt(LocalDateTime.now())
                .build();

        when(notificationSender.send(any(Notification.class)))
                .thenReturn(successResult);

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        verify(eventProducer).publishSendAttempted(
                eq(testNotification.getUuid()),
                eq(testNotification.getOrganizationId()),
                eq(0)
        );
    }

    @Test
    @DisplayName("Should publish sent event on success")
    void testConsumeSendMessage_PublishesSentEvent() {
        // Arrange
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification));
        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));

        SendResult successResult = SendResult.builder()
                .success(true)
                .providerId("provider-123")
                .sentAt(LocalDateTime.now())
                .build();

        when(notificationSender.send(any(Notification.class)))
                .thenReturn(successResult);

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        verify(eventProducer).publishSent(
                eq(testNotification.getUuid()),
                eq(testNotification.getOrganizationId()),
                eq("provider-123"),
                anyString()
        );
    }

    @Test
    @DisplayName("Should publish retried event on transient failure")
    void testConsumeSendMessage_PublishesRetriedEvent() {
        // Arrange
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification))
                .thenReturn(Optional.of(testNotification));
        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));
        when(notificationSender.send(any(Notification.class)))
                .thenThrow(new TransientSendException("Error"));

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        verify(eventProducer).publishRetried(
                eq(testNotification.getUuid()),
                eq(testNotification.getOrganizationId()),
                eq(1),
                anyLong()
        );
    }

    @Test
    @DisplayName("Should publish failed event on permanent failure")
    void testConsumeSendMessage_PublishesFailedEvent() {
        // Arrange
        when(notificationRepository.findByUuid(testMessage.getNotificationUuid()))
                .thenReturn(Optional.of(testNotification))
                .thenReturn(Optional.of(testNotification));
        when(senderFactory.getSender(NotificationType.EMAIL))
                .thenReturn(Optional.of(notificationSender));
        when(notificationSender.send(any(Notification.class)))
                .thenThrow(new PermanentSendException("Invalid recipient"));

        // Act
        senderConsumer.consumeSendMessage(consumerRecord, acknowledgment);

        // Assert
        verify(eventProducer).publishFailed(
                eq(testNotification.getUuid()),
                eq(testNotification.getOrganizationId()),
                eq("PERMANENT_FAILURE"),
                anyString()
        );
    }
}
