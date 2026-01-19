package com.notification.notification.validator;

import com.notification.notification.dto.NotificationRequest;
import com.notification.notification.entity.NotificationType;
import com.notification.notification.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotificationRequestValidatorTest {

    private NotificationRequestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new NotificationRequestValidator();
    }

    @Test
    void testValidateEmailNotificationSuccess() {
        // Arrange
        NotificationRequest request = NotificationRequest.builder()
                .title("Test")
                .message("Test message")
                .recipient("test-user")
                .recipientEmail("test@example.com")
                .type(NotificationType.EMAIL)
                .build();

        // Act & Assert
        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void testValidateEmailNotificationWithMissingEmail() {
        // Arrange
        NotificationRequest request = NotificationRequest.builder()
                .title("Test")
                .message("Test message")
                .recipient("test-user")
                .recipientEmail(null)
                .type(NotificationType.EMAIL)
                .build();

        // Act & Assert
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> validator.validate(request)
        );
        assertTrue(exception.getMessage().contains("Recipient email is required"));
    }

    @Test
    void testValidateEmailNotificationWithInvalidEmail() {
        // Arrange
        NotificationRequest request = NotificationRequest.builder()
                .title("Test")
                .message("Test message")
                .recipient("test-user")
                .recipientEmail("invalid-email")
                .type(NotificationType.EMAIL)
                .build();

        // Act & Assert
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> validator.validate(request)
        );
        assertTrue(exception.getMessage().contains("Invalid email address"));
    }

    @Test
    void testValidateEmailNotificationWithBlankEmail() {
        // Arrange
        NotificationRequest request = NotificationRequest.builder()
                .title("Test")
                .message("Test message")
                .recipient("test-user")
                .recipientEmail("")
                .type(NotificationType.EMAIL)
                .build();

        // Act & Assert
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> validator.validate(request)
        );
        assertTrue(exception.getMessage().contains("Recipient email is required"));
    }

    @Test
    void testValidateSmsNotification() {
        // Arrange
        NotificationRequest request = NotificationRequest.builder()
                .title("Test")
                .message("Test message")
                .recipient("test-user")
                .type(NotificationType.SMS)
                .build();

        // Act & Assert
        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void testValidatePushNotification() {
        // Arrange
        NotificationRequest request = NotificationRequest.builder()
                .title("Test")
                .message("Test message")
                .recipient("test-user")
                .type(NotificationType.PUSH)
                .build();

        // Act & Assert
        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void testValidateInAppNotification() {
        // Arrange
        NotificationRequest request = NotificationRequest.builder()
                .title("Test")
                .message("Test message")
                .recipient("test-user")
                .type(NotificationType.IN_APP)
                .build();

        // Act & Assert
        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void testValidateWithNullType() {
        // Arrange
        NotificationRequest request = NotificationRequest.builder()
                .title("Test")
                .message("Test message")
                .recipient("test-user")
                .type(null)
                .build();

        // Act & Assert
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> validator.validate(request)
        );
        assertTrue(exception.getMessage().contains("Notification type is required"));
    }
}
