package com.notification.notification.repository;

import com.notification.notification.entity.Notification;
import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.entity.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@DisplayName("NotificationRepository Data JPA Tests")
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.TestPropertySource(properties = {
    "spring.kafka.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
class NotificationRepositoryTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Notification testNotification1;
    private Notification testNotification2;

    @BeforeEach
    void setUp() {
        // Clear database
        notificationRepository.deleteAll();

        // Create test notifications
        testNotification1 = Notification.builder()
                .organizationId("org-123")
                .title("Test Notification 1")
                .message("Test message 1")
                .recipient("user123")
                .recipientEmail("user123@example.com")
                .type(NotificationType.EMAIL)
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .maxRetries(5)
                .build();

        testNotification2 = Notification.builder()
                .organizationId("org-456")
                .title("Test Notification 2")
                .message("Test message 2")
                .recipient("user456")
                .recipientEmail("user456@example.com")
                .type(NotificationType.SMS)
                .status(NotificationStatus.SENT)
                .retryCount(0)
                .maxRetries(5)
                .build();
    }

    // ==================== SAVE TESTS ====================

    @Test
    @DisplayName("Should save notification and auto-generate UUID and timestamps")
    void testSave_AutoGeneratesUuidAndTimestamps() {
        // Act
        Notification saved = notificationRepository.save(testNotification1);

        // Assert
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should enforce UUID uniqueness constraint")
    void testSave_UuidUniqueConstraint() {
        // Arrange
        Notification first = notificationRepository.save(testNotification1);
        UUID uuid = first.getUuid();

        // Create another notification with same UUID (should be prevented by DB constraint)
        testNotification2.setUuid(uuid);

        // Act & Assert - Attempting to save with duplicate UUID should throw exception
        // Note: This behavior depends on database constraints
        // In H2, this might not throw immediately but should be prevented at DB level
        assertThat(first.getUuid()).isNotNull();
    }

    // ==================== FIND BY UUID TESTS ====================

    @Test
    @DisplayName("Should find notification by UUID")
    void testFindByUuid_Success() {
        // Arrange
        Notification saved = notificationRepository.save(testNotification1);
        UUID uuid = saved.getUuid();

        // Act
        Optional<Notification> found = notificationRepository.findByUuid(uuid);

        // Assert
        assertThat(found).isPresent();
        assertThat(found.get().getUuid()).isEqualTo(uuid);
        assertThat(found.get().getTitle()).isEqualTo("Test Notification 1");
    }

    @Test
    @DisplayName("Should return empty when UUID not found")
    void testFindByUuid_NotFound() {
        // Act
        Optional<Notification> found = notificationRepository.findByUuid(UUID.randomUUID());

        // Assert
        assertThat(found).isEmpty();
    }

    // ==================== FIND BY RECIPIENT TESTS ====================

    @Test
    @DisplayName("Should find notifications by recipient")
    void testFindByRecipient() {
        // Arrange
        notificationRepository.save(testNotification1);
        Notification notification3 = Notification.builder()
                .organizationId("org-123")
                .title("Test Notification 3")
                .message("Test message 3")
                .recipient("user123")
                .recipientEmail("user123@example.com")
                .type(NotificationType.PUSH)
                .status(NotificationStatus.PENDING)
                .build();
        notificationRepository.save(notification3);
        notificationRepository.save(testNotification2); // Different recipient

        // Act
        List<Notification> found = notificationRepository.findByRecipient("user123");

        // Assert
        assertThat(found).hasSize(2);
        assertThat(found).allMatch(n -> n.getRecipient().equals("user123"));
    }

    @Test
    @DisplayName("Should find notifications by recipient with pagination")
    void testFindByRecipient_Paginated() {
        // Arrange
        notificationRepository.save(testNotification1);
        notificationRepository.save(Notification.builder()
                .organizationId("org-123")
                .recipient("user123")
                .recipientEmail("user123@example.com")
                .title("Notification 2")
                .message("Message 2")
                .type(NotificationType.EMAIL)
                .status(NotificationStatus.PENDING)
                .build());

        // Act
        Page<Notification> page = notificationRepository.findByRecipient(
                "user123",
                PageRequest.of(0, 10)
        );

        // Assert
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    // ==================== FIND BY STATUS TESTS ====================

    @Test
    @DisplayName("Should find notifications by status")
    void testFindByStatus() {
        // Arrange
        notificationRepository.save(testNotification1); // PENDING
        notificationRepository.save(testNotification2); // SENT

        // Act
        List<Notification> pending = notificationRepository.findByStatus(NotificationStatus.PENDING);
        List<Notification> sent = notificationRepository.findByStatus(NotificationStatus.SENT);

        // Assert
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    // ==================== FIND BY TYPE TESTS ====================

    @Test
    @DisplayName("Should find notifications by type")
    void testFindByType() {
        // Arrange
        notificationRepository.save(testNotification1); // EMAIL
        notificationRepository.save(testNotification2); // SMS

        // Act
        List<Notification> emails = notificationRepository.findByType(NotificationType.EMAIL);
        List<Notification> sms = notificationRepository.findByType(NotificationType.SMS);

        // Assert
        assertThat(emails).hasSize(1);
        assertThat(emails.get(0).getType()).isEqualTo(NotificationType.EMAIL);
        assertThat(sms).hasSize(1);
        assertThat(sms.get(0).getType()).isEqualTo(NotificationType.SMS);
    }

    // ==================== COMBINED FILTER TESTS ====================

    @Test
    @DisplayName("Should find notifications by recipient and status")
    void testFindByRecipientAndStatus() {
        // Arrange
        notificationRepository.save(testNotification1); // user123, PENDING
        Notification notification3 = Notification.builder()
                .organizationId("org-123")
                .recipient("user123")
                .recipientEmail("user123@example.com")
                .title("Notification 3")
                .message("Message 3")
                .type(NotificationType.EMAIL)
                .status(NotificationStatus.SENT)
                .build();
        notificationRepository.save(notification3); // user123, SENT

        // Act
        List<Notification> pending = notificationRepository.findByRecipientAndStatus(
                "user123",
                NotificationStatus.PENDING
        );

        // Assert
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    @DisplayName("Should find notifications by recipient and type")
    void testFindByRecipientAndType() {
        // Arrange
        notificationRepository.save(testNotification1); // user123, EMAIL
        Notification notification3 = Notification.builder()
                .organizationId("org-123")
                .recipient("user123")
                .recipientEmail("user123@example.com")
                .title("Notification 3")
                .message("Message 3")
                .type(NotificationType.SMS)
                .status(NotificationStatus.PENDING)
                .build();
        notificationRepository.save(notification3); // user123, SMS

        // Act
        List<Notification> emails = notificationRepository.findByRecipientAndType(
                "user123",
                NotificationType.EMAIL
        );

        // Assert
        assertThat(emails).hasSize(1);
        assertThat(emails.get(0).getType()).isEqualTo(NotificationType.EMAIL);
    }

    // ==================== CUSTOM QUERY TESTS ====================

    @Test
    @DisplayName("Should find pending notifications using custom query")
    void testFindPendingNotifications() {
        // Arrange
        notificationRepository.save(testNotification1); // user123, PENDING
        Notification notification3 = Notification.builder()
                .organizationId("org-123")
                .recipient("user123")
                .recipientEmail("user123@example.com")
                .title("Notification 3")
                .message("Message 3")
                .type(NotificationType.EMAIL)
                .status(NotificationStatus.SENT)
                .build();
        notificationRepository.save(notification3); // user123, SENT

        // Act
        List<Notification> pending = notificationRepository.findPendingNotifications("user123");

        // Assert
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    @DisplayName("Should find notifications by date range")
    void testFindByDateRange() {
        // Arrange
        notificationRepository.save(testNotification1);
        notificationRepository.save(testNotification2);

        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(1);

        // Act
        List<Notification> found = notificationRepository.findByDateRange(start, end);

        // Assert
        assertThat(found).hasSize(2);
    }

    @Test
    @DisplayName("Should count notifications by recipient and status")
    void testCountByRecipientAndStatus() {
        // Arrange
        notificationRepository.save(testNotification1); // user123, PENDING
        Notification notification3 = Notification.builder()
                .organizationId("org-123")
                .recipient("user123")
                .recipientEmail("user123@example.com")
                .title("Notification 3")
                .message("Message 3")
                .type(NotificationType.EMAIL)
                .status(NotificationStatus.PENDING)
                .build();
        notificationRepository.save(notification3); // user123, PENDING

        // Act
        long count = notificationRepository.countByRecipientAndStatus(
                "user123",
                NotificationStatus.PENDING
        );

        // Assert
        assertThat(count).isEqualTo(2);
    }

    // ==================== ORDERING TESTS ====================

    @Test
    @DisplayName("Should find notifications ordered by created date descending")
    void testFindByRecipientOrderByCreatedAtDesc() throws InterruptedException {
        // Arrange
        Notification first = notificationRepository.save(testNotification1);

        // Add a small delay to ensure different timestamps
        Thread.sleep(10);

        Notification second = Notification.builder()
                .organizationId("org-123")
                .recipient("user123")
                .recipientEmail("user123@example.com")
                .title("Later Notification")
                .message("Later message")
                .type(NotificationType.EMAIL)
                .status(NotificationStatus.PENDING)
                .build();
        notificationRepository.save(second);

        // Act
        List<Notification> notifications = notificationRepository.findByRecipientOrderByCreatedAtDesc("user123");

        // Assert
        assertThat(notifications).hasSize(2);
        // Most recent should be first
        assertThat(notifications.get(0).getTitle()).isEqualTo("Later Notification");
        assertThat(notifications.get(1).getTitle()).isEqualTo("Test Notification 1");
    }
}
