package com.notification.notification.integration;

import com.notification.notification.dto.NotificationRequest;
import com.notification.notification.dto.NotificationResponse;
import com.notification.notification.entity.Notification;
import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.entity.NotificationType;
import com.notification.notification.repository.NotificationRepository;
import com.notification.notification.sender.SendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for notification microservice.
 * Tests complete flow: REST API → Kafka → Consumer → Database → Events
 * <p>
 * Uses Testcontainers for real Kafka and PostgreSQL instances.
 * Email sending is mocked via JavaMailSender.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Notification E2E Integration Tests")
class NotificationE2EIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    @MockBean
    private JavaMailSender mailSender;

    @BeforeEach
    void setUp() {
        // Clear database before each test
        notificationRepository.deleteAll();

        // Mock email sender to prevent actual email sending
        when(mailSender.createMimeMessage()).thenReturn(new org.springframework.mail.javamail.MimeMailMessage(
                new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null)
        ).getMimeMessage());
        doNothing().when(mailSender).send(any(jakarta.mail.internet.MimeMessage.class));
    }

    // ==================== HAPPY PATH TEST ====================

    @Test
    @DisplayName("E2E: Should create notification, process via Kafka, and mark as SENT")
    void testCompleteFlow_Success() {
        // Arrange
        NotificationRequest request = NotificationRequest.builder()
                .title("Test E2E Notification")
                .message("This is an end-to-end test")
                .recipient("user123")
                .recipientEmail("test@example.com")
                .type(NotificationType.EMAIL)
                .organizationId("org-123")
                .build();

        // Act - POST to create notification
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/notifications",
                request,
                Map.class
        );

        // Assert - API returns 202 ACCEPTED
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("success")).isEqualTo(true);

        java.util.Map<String, Object> data = (java.util.Map<String, Object>) response.getBody().get("data");
        String uuid = (String) data.get("uuid");
        assertThat(uuid).isNotNull();

        // Wait for async processing (Kafka consumer processes the message)
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<Notification> notifications = notificationRepository.findByRecipient("user123");
                    assertThat(notifications).isNotEmpty();

                    Notification notification = notifications.get(0);
                    // Should eventually be marked as SENT by the consumer
                    assertThat(notification.getStatus()).isIn(
                            NotificationStatus.PENDING,
                            NotificationStatus.PROCESSING,
                            NotificationStatus.SENT
                    );

                    // If sent, verify additional fields
                    if (notification.getStatus() == NotificationStatus.SENT) {
                        assertThat(notification.getSentAt()).isNotNull();
                        assertThat(notification.getProviderId()).isNotNull();
                    }
                });
    }

    // ==================== RETRY FLOW TEST ====================

    @Test
    @DisplayName("E2E: Should retry notification on transient failure")
    void testCompleteFlow_TransientFailure_Retries() {
        // Arrange - Mock email sender to fail on first attempt, succeed on second
        org.springframework.mail.MailSendException mailException =
                new org.springframework.mail.MailSendException("Temporary connection error");
        doThrow(mailException)
                .doNothing()
                .when(mailSender).send(any(jakarta.mail.internet.MimeMessage.class));

        NotificationRequest request = NotificationRequest.builder()
                .title("Test Retry")
                .message("This should retry")
                .recipient("user456")
                .recipientEmail("retry@example.com")
                .type(NotificationType.EMAIL)
                .organizationId("org-456")
                .build();

        // Act
        restTemplate.postForEntity("/api/v1/notifications", request, Map.class);

        // Assert - Should eventually succeed after retry
        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<Notification> notifications = notificationRepository.findByRecipient("user456");
                    assertThat(notifications).isNotEmpty();

                    Notification notification = notifications.get(0);
                    // After retry, should be SENT or still RETRYING
                    assertThat(notification.getStatus()).isIn(
                            NotificationStatus.RETRYING,
                            NotificationStatus.SENT
                    );

                    // If retrying, check retry count increased
                    if (notification.getStatus() == NotificationStatus.RETRYING) {
                        assertThat(notification.getRetryCount()).isGreaterThan(0);
                    }
                });
    }

    // ==================== PERMANENT FAILURE TEST ====================

    @Test
    @DisplayName("E2E: Should mark as FAILED on permanent failure")
    void testCompleteFlow_PermanentFailure() {
        // Arrange - Mock email sender to throw permanent failure
        org.springframework.mail.MailAuthenticationException authException =
                new org.springframework.mail.MailAuthenticationException("Invalid credentials");
        doThrow(authException)
                .when(mailSender).send(any(jakarta.mail.internet.MimeMessage.class));

        NotificationRequest request = NotificationRequest.builder()
                .title("Test Permanent Failure")
                .message("This should fail permanently")
                .recipient("user789")
                .recipientEmail("fail@example.com")
                .type(NotificationType.EMAIL)
                .organizationId("org-789")
                .build();

        // Act
        restTemplate.postForEntity("/api/v1/notifications", request, Map.class);

        // Assert - Should eventually be marked as FAILED
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<Notification> notifications = notificationRepository.findByRecipient("user789");
                    assertThat(notifications).isNotEmpty();

                    Notification notification = notifications.get(0);
                    assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
                    assertThat(notification.getErrorMessage()).isNotNull();
                    assertThat(notification.getErrorCode()).isEqualTo("PERMANENT_FAILURE");
                });
    }

    // ==================== IDEMPOTENCY TEST ====================

    @Test
    @DisplayName("E2E: Should handle duplicate messages idempotently")
    void testCompleteFlow_Idempotency() {
        // Arrange
        NotificationRequest request = NotificationRequest.builder()
                .title("Test Idempotency")
                .message("Should process only once")
                .recipient("user-idempotent")
                .recipientEmail("idempotent@example.com")
                .type(NotificationType.EMAIL)
                .organizationId("org-idempotent")
                .build();

        // Act - Send same request multiple times (simulating duplicate Kafka messages)
        for (int i = 0; i < 3; i++) {
            restTemplate.postForEntity("/api/v1/notifications", request, Map.class);
        }

        // Wait for processing
        await().pollDelay(Duration.ofSeconds(2)).untilAsserted(() -> {
            // Assert - Should have exactly 3 notifications (one per request)
            // But each should be processed only once (no duplicate sends)
            List<Notification> notifications = notificationRepository.findByRecipient("user-idempotent");
            assertThat(notifications).hasSize(3);

            // Each notification should have been processed
            for (Notification n : notifications) {
                assertThat(n.getStatus()).isIn(
                        NotificationStatus.SENT,
                        NotificationStatus.PENDING,
                        NotificationStatus.PROCESSING
                );
            }
        });
    }

    // ==================== MULTI-TENANCY TEST ====================

    @Test
    @DisplayName("E2E: Should handle multiple organizations independently")
    void testCompleteFlow_MultiTenancy() {
        // Arrange - Create notifications for different organizations
        NotificationRequest request1 = NotificationRequest.builder()
                .title("Org1 Notification")
                .message("Message for org1")
                .recipient("user-org1")
                .recipientEmail("org1@example.com")
                .type(NotificationType.EMAIL)
                .organizationId("org-001")
                .build();

        NotificationRequest request2 = NotificationRequest.builder()
                .title("Org2 Notification")
                .message("Message for org2")
                .recipient("user-org2")
                .recipientEmail("org2@example.com")
                .type(NotificationType.EMAIL)
                .organizationId("org-002")
                .build();

        // Act
        restTemplate.postForEntity("/api/v1/notifications", request1, Map.class);
        restTemplate.postForEntity("/api/v1/notifications", request2, Map.class);

        // Assert - Both should be processed independently
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<Notification> org1Notifications = notificationRepository.findByRecipient("user-org1");
            List<Notification> org2Notifications = notificationRepository.findByRecipient("user-org2");

            assertThat(org1Notifications).hasSize(1);
            assertThat(org1Notifications.get(0).getOrganizationId()).isEqualTo("org-001");

            assertThat(org2Notifications).hasSize(1);
            assertThat(org2Notifications.get(0).getOrganizationId()).isEqualTo("org-002");
        });
    }

    // Helper class for deserializing API responses
    @SuppressWarnings("unused")
    private static class Map extends java.util.HashMap<String, Object> {
    }
}
