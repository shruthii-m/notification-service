package com.notification.notification.service;

import com.notification.notification.dto.NotificationRequest;
import com.notification.notification.dto.NotificationResponse;
import com.notification.notification.entity.Notification;
import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.entity.NotificationType;
import com.notification.notification.exception.BadRequestException;
import com.notification.notification.exception.ResourceNotFoundException;
import com.notification.notification.messaging.dto.NotificationMessage;
import com.notification.notification.messaging.producer.EventProducer;
import com.notification.notification.messaging.producer.NotificationProducer;
import com.notification.notification.repository.NotificationRepository;
import com.notification.notification.validator.NotificationRequestValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationServiceImpl Unit Tests")
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationProducer notificationProducer;

    @Mock
    private EventProducer eventProducer;

    @Mock
    private NotificationRequestValidator validator;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private NotificationRequest testRequest;
    private Notification testNotification;

    @BeforeEach
    void setUp() {
        testRequest = NotificationRequest.builder()
                .title("Test Notification")
                .message("Test message content")
                .recipient("user123")
                .recipientEmail("test@example.com")
                .type(NotificationType.EMAIL)
                .organizationId("org-123")
                .build();

        testNotification = Notification.builder()
                .id(1L)
                .uuid(UUID.randomUUID())
                .organizationId("org-123")
                .title("Test Notification")
                .message("Test message content")
                .recipient("user123")
                .recipientEmail("test@example.com")
                .type(NotificationType.EMAIL)
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .maxRetries(5)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ==================== CREATE NOTIFICATION TESTS ====================

    @Test
    @DisplayName("Should create notification with PENDING status and publish to Kafka")
    void testCreateNotification_Success() {
        // Arrange
        when(notificationRepository.save(any(Notification.class))).thenReturn(testNotification);
        doNothing().when(validator).validate(any(NotificationRequest.class));

        // Act
        NotificationResponse response = notificationService.createNotification(testRequest);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(response.getTitle()).isEqualTo(testRequest.getTitle());
        assertThat(response.getMessage()).isEqualTo(testRequest.getMessage());
        assertThat(response.getRecipient()).isEqualTo(testRequest.getRecipient());

        // Verify validator was called
        verify(validator).validate(testRequest);

        // Verify notification was saved
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notificationCaptor.capture());
        Notification savedNotification = notificationCaptor.getValue();
        assertThat(savedNotification.getStatus()).isEqualTo(NotificationStatus.PENDING);

        // Verify Kafka message published
        ArgumentCaptor<NotificationMessage> messageCaptor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationProducer).publishToSend(messageCaptor.capture());
        NotificationMessage message = messageCaptor.getValue();
        assertThat(message.getNotificationUuid()).isEqualTo(testNotification.getUuid());
        assertThat(message.getOrganizationId()).isEqualTo(testNotification.getOrganizationId());
        assertThat(message.getRetryCount()).isEqualTo(0);

        // Verify event published
        verify(eventProducer).publishCreated(testNotification.getUuid(), testNotification.getOrganizationId());
    }

    @Test
    @DisplayName("Should throw BadRequestException when validation fails")
    void testCreateNotification_ValidationFailure() {
        // Arrange
        doThrow(new BadRequestException("Invalid email"))
                .when(validator).validate(any(NotificationRequest.class));

        // Act & Assert
        assertThatThrownBy(() -> notificationService.createNotification(testRequest))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid email");

        verify(notificationRepository, never()).save(any());
        verify(notificationProducer, never()).publishToSend(any());
    }

    // ==================== GET NOTIFICATION TESTS ====================

    @Test
    @DisplayName("Should get notification by ID")
    void testGetNotificationById_Success() {
        // Arrange
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(testNotification));

        // Act
        NotificationResponse response = notificationService.getNotificationById(1L);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(testNotification.getId());
        assertThat(response.getTitle()).isEqualTo(testNotification.getTitle());
        verify(notificationRepository).findById(1L);
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when notification not found")
    void testGetNotificationById_NotFound() {
        // Arrange
        when(notificationRepository.findById(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> notificationService.getNotificationById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Notification")
                .hasMessageContaining("999");

        verify(notificationRepository).findById(999L);
    }

    @Test
    @DisplayName("Should get all notifications")
    void testGetAllNotifications() {
        // Arrange
        Notification notification2 = Notification.builder()
                .id(2L)
                .uuid(UUID.randomUUID())
                .title("Second Notification")
                .message("Second message")
                .recipient("user456")
                .recipientEmail("test2@example.com")
                .type(NotificationType.SMS)
                .status(NotificationStatus.SENT)
                .createdAt(LocalDateTime.now())
                .build();

        when(notificationRepository.findAll()).thenReturn(Arrays.asList(testNotification, notification2));

        // Act
        List<NotificationResponse> responses = notificationService.getAllNotifications();

        // Assert
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getId()).isEqualTo(testNotification.getId());
        assertThat(responses.get(1).getId()).isEqualTo(notification2.getId());
        verify(notificationRepository).findAll();
    }

    @Test
    @DisplayName("Should get all notifications with pagination")
    void testGetAllNotifications_Paginated() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        Page<Notification> page = new PageImpl<>(Arrays.asList(testNotification));
        when(notificationRepository.findAll(pageable)).thenReturn(page);

        // Act
        Page<NotificationResponse> responses = notificationService.getAllNotifications(pageable);

        // Assert
        assertThat(responses).hasSize(1);
        assertThat(responses.getContent().get(0).getId()).isEqualTo(testNotification.getId());
        verify(notificationRepository).findAll(pageable);
    }

    // ==================== FILTER TESTS ====================

    @Test
    @DisplayName("Should get notifications by recipient")
    void testGetNotificationsByRecipient() {
        // Arrange
        String recipient = "user123";
        when(notificationRepository.findByRecipientOrderByCreatedAtDesc(recipient))
                .thenReturn(Arrays.asList(testNotification));

        // Act
        List<NotificationResponse> responses = notificationService.getNotificationsByRecipient(recipient);

        // Assert
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getRecipient()).isEqualTo(recipient);
        verify(notificationRepository).findByRecipientOrderByCreatedAtDesc(recipient);
    }

    @Test
    @DisplayName("Should get notifications by recipient with pagination")
    void testGetNotificationsByRecipient_Paginated() {
        // Arrange
        String recipient = "user123";
        Pageable pageable = PageRequest.of(0, 10);
        Page<Notification> page = new PageImpl<>(Arrays.asList(testNotification));
        when(notificationRepository.findByRecipient(recipient, pageable)).thenReturn(page);

        // Act
        Page<NotificationResponse> responses = notificationService.getNotificationsByRecipient(recipient, pageable);

        // Assert
        assertThat(responses).hasSize(1);
        assertThat(responses.getContent().get(0).getRecipient()).isEqualTo(recipient);
        verify(notificationRepository).findByRecipient(recipient, pageable);
    }

    @Test
    @DisplayName("Should get notifications by status")
    void testGetNotificationsByStatus() {
        // Arrange
        NotificationStatus status = NotificationStatus.PENDING;
        when(notificationRepository.findByStatus(status)).thenReturn(Arrays.asList(testNotification));

        // Act
        List<NotificationResponse> responses = notificationService.getNotificationsByStatus(status);

        // Assert
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getStatus()).isEqualTo(status);
        verify(notificationRepository).findByStatus(status);
    }

    @Test
    @DisplayName("Should get notifications by type")
    void testGetNotificationsByType() {
        // Arrange
        NotificationType type = NotificationType.EMAIL;
        when(notificationRepository.findByType(type)).thenReturn(Arrays.asList(testNotification));

        // Act
        List<NotificationResponse> responses = notificationService.getNotificationsByType(type);

        // Assert
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getType()).isEqualTo(type);
        verify(notificationRepository).findByType(type);
    }

    // ==================== UPDATE NOTIFICATION TESTS ====================

    @Test
    @DisplayName("Should update notification fields")
    void testUpdateNotification_Success() {
        // Arrange
        NotificationRequest updateRequest = NotificationRequest.builder()
                .title("Updated Title")
                .message("Updated message")
                .recipient("user456")
                .recipientEmail("updated@example.com")
                .type(NotificationType.SMS)
                .build();

        Notification updatedNotification = Notification.builder()
                .id(testNotification.getId())
                .uuid(testNotification.getUuid())
                .title("Updated Title")
                .message("Updated message")
                .recipient("user456")
                .recipientEmail("updated@example.com")
                .type(NotificationType.SMS)
                .status(testNotification.getStatus())
                .createdAt(testNotification.getCreatedAt())
                .build();

        when(notificationRepository.findById(1L)).thenReturn(Optional.of(testNotification));
        when(notificationRepository.save(any(Notification.class))).thenReturn(updatedNotification);

        // Act
        NotificationResponse response = notificationService.updateNotification(1L, updateRequest);

        // Assert
        assertThat(response.getTitle()).isEqualTo("Updated Title");
        assertThat(response.getMessage()).isEqualTo("Updated message");
        assertThat(response.getRecipient()).isEqualTo("user456");
        verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    @DisplayName("Should throw exception when updating non-existent notification")
    void testUpdateNotification_NotFound() {
        // Arrange
        when(notificationRepository.findById(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> notificationService.updateNotification(999L, testRequest))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================== UPDATE STATUS TESTS ====================

    @Test
    @DisplayName("Should update notification status to SENT and set sentAt")
    void testUpdateNotificationStatus_ToSent() {
        // Arrange
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(testNotification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        NotificationResponse response = notificationService.updateNotificationStatus(1L, NotificationStatus.SENT);

        // Assert
        assertThat(response.getStatus()).isEqualTo(NotificationStatus.SENT);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("Should update notification status to READ and set readAt")
    void testUpdateNotificationStatus_ToRead() {
        // Arrange
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(testNotification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        NotificationResponse response = notificationService.updateNotificationStatus(1L, NotificationStatus.READ);

        // Assert
        assertThat(response.getStatus()).isEqualTo(NotificationStatus.READ);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("Should mark notification as read")
    void testMarkAsRead() {
        // Arrange
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(testNotification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        NotificationResponse response = notificationService.markAsRead(1L);

        // Assert
        assertThat(response.getStatus()).isEqualTo(NotificationStatus.READ);
        verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    @DisplayName("Should mark notification as sent")
    void testMarkAsSent() {
        // Arrange
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(testNotification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        NotificationResponse response = notificationService.markAsSent(1L);

        // Assert
        assertThat(response.getStatus()).isEqualTo(NotificationStatus.SENT);
        verify(notificationRepository).save(any(Notification.class));
    }

    // ==================== DELETE NOTIFICATION TESTS ====================

    @Test
    @DisplayName("Should delete notification")
    void testDeleteNotification_Success() {
        // Arrange
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(testNotification));
        doNothing().when(notificationRepository).delete(testNotification);

        // Act
        notificationService.deleteNotification(1L);

        // Assert
        verify(notificationRepository).findById(1L);
        verify(notificationRepository).delete(testNotification);
    }

    @Test
    @DisplayName("Should throw exception when deleting non-existent notification")
    void testDeleteNotification_NotFound() {
        // Arrange
        when(notificationRepository.findById(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> notificationService.deleteNotification(999L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(notificationRepository, never()).delete(any());
    }

    // ==================== COUNT TESTS ====================

    @Test
    @DisplayName("Should count unread notifications for recipient")
    void testCountUnreadNotifications() {
        // Arrange
        String recipient = "user123";
        when(notificationRepository.countByRecipientAndStatus(recipient, NotificationStatus.PENDING))
                .thenReturn(5L);

        // Act
        long count = notificationService.countUnreadNotifications(recipient);

        // Assert
        assertThat(count).isEqualTo(5L);
        verify(notificationRepository).countByRecipientAndStatus(recipient, NotificationStatus.PENDING);
    }

    @Test
    @DisplayName("Should return zero when no unread notifications")
    void testCountUnreadNotifications_Zero() {
        // Arrange
        String recipient = "user123";
        when(notificationRepository.countByRecipientAndStatus(recipient, NotificationStatus.PENDING))
                .thenReturn(0L);

        // Act
        long count = notificationService.countUnreadNotifications(recipient);

        // Assert
        assertThat(count).isEqualTo(0L);
    }

    // ==================== RESPONSE MAPPING TESTS ====================

    @Test
    @DisplayName("Should map notification to response with all fields")
    void testMapToResponse_AllFields() {
        // Arrange
        testNotification.setRetryCount(2);
        testNotification.setErrorCode("TEMP_ERROR");
        testNotification.setErrorMessage("Temporary failure");
        testNotification.setProviderId("provider-123");
        testNotification.setProviderName("EmailProvider");
        testNotification.setSentAt(LocalDateTime.now());
        testNotification.setReadAt(LocalDateTime.now());

        when(notificationRepository.findById(1L)).thenReturn(Optional.of(testNotification));

        // Act
        NotificationResponse response = notificationService.getNotificationById(1L);

        // Assert
        assertThat(response.getId()).isEqualTo(testNotification.getId());
        assertThat(response.getUuid()).isEqualTo(testNotification.getUuid());
        assertThat(response.getOrganizationId()).isEqualTo(testNotification.getOrganizationId());
        assertThat(response.getRetryCount()).isEqualTo(2);
        assertThat(response.getErrorCode()).isEqualTo("TEMP_ERROR");
        assertThat(response.getErrorMessage()).isEqualTo("Temporary failure");
        assertThat(response.getProviderId()).isEqualTo("provider-123");
        assertThat(response.getProviderName()).isEqualTo("EmailProvider");
        assertThat(response.getSentAt()).isNotNull();
        assertThat(response.getReadAt()).isNotNull();
    }
}
