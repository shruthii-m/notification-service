package com.notification.notification.sender.email;

import com.notification.notification.entity.Notification;
import com.notification.notification.entity.NotificationType;
import com.notification.notification.exception.PermanentSendException;
import com.notification.notification.exception.TransientSendException;
import com.notification.notification.sender.SendResult;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmtpEmailSenderTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private MimeMessage mimeMessage;

    private SmtpEmailSender emailSender;

    @BeforeEach
    void setUp() {
        emailSender = new SmtpEmailSender(mailSender);
        ReflectionTestUtils.setField(emailSender, "defaultFromEmail", "noreply@test.com");
        ReflectionTestUtils.setField(emailSender, "defaultFromName", "Test Service");
        ReflectionTestUtils.setField(emailSender, "enabled", true);
    }

    @Test
    void testSendEmailSuccess() {
        // Arrange
        Notification notification = Notification.builder()
                .id(1L)
                .title("Test Email")
                .message("Test message")
                .recipientEmail("test@example.com")
                .recipient("test-user")
                .type(NotificationType.EMAIL)
                .build();

        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        // Act
        SendResult result = emailSender.send(notification);

        // Assert
        assertTrue(result.isSuccess());
        assertEquals("1", result.getProviderId());
        assertNotNull(result.getSentAt());
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void testSendEmailWithMissingRecipientEmail() {
        // Arrange
        Notification notification = Notification.builder()
                .id(1L)
                .title("Test Email")
                .message("Test message")
                .recipientEmail(null)
                .recipient("test-user")
                .type(NotificationType.EMAIL)
                .build();

        // Act & Assert
        assertThrows(PermanentSendException.class, () -> emailSender.send(notification));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void testSendEmailAuthenticationError() {
        // Arrange
        Notification notification = Notification.builder()
                .id(1L)
                .title("Test Email")
                .message("Test message")
                .recipientEmail("test@example.com")
                .recipient("test-user")
                .type(NotificationType.EMAIL)
                .build();

        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailAuthenticationException("Authentication failed"))
                .when(mailSender).send(any(MimeMessage.class));

        // Act & Assert
        assertThrows(PermanentSendException.class, () -> emailSender.send(notification));
    }

    @Test
    void testSendEmailTransientError() {
        // Arrange
        Notification notification = Notification.builder()
                .id(1L)
                .title("Test Email")
                .message("Test message")
                .recipientEmail("test@example.com")
                .recipient("test-user")
                .type(NotificationType.EMAIL)
                .build();

        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("Connection timeout"))
                .when(mailSender).send(any(MimeMessage.class));

        // Act & Assert
        assertThrows(TransientSendException.class, () -> emailSender.send(notification));
    }

    @Test
    void testSendEmailPermanentError() {
        // Arrange
        Notification notification = Notification.builder()
                .id(1L)
                .title("Test Email")
                .message("Test message")
                .recipientEmail("test@example.com")
                .recipient("test-user")
                .type(NotificationType.EMAIL)
                .build();

        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("Invalid recipient"))
                .when(mailSender).send(any(MimeMessage.class));

        // Act & Assert
        assertThrows(PermanentSendException.class, () -> emailSender.send(notification));
    }

    @Test
    void testGetSupportedType() {
        // Act & Assert
        assertEquals(NotificationType.EMAIL, emailSender.getSupportedType());
    }

    @Test
    void testIsAvailable() {
        // Act & Assert
        assertTrue(emailSender.isAvailable());
    }

    @Test
    void testIsNotAvailableWhenDisabled() {
        // Arrange
        ReflectionTestUtils.setField(emailSender, "enabled", false);

        // Act & Assert
        assertFalse(emailSender.isAvailable());
    }

    @Test
    void testSendEmailWhenNotAvailable() {
        // Arrange
        ReflectionTestUtils.setField(emailSender, "enabled", false);

        Notification notification = Notification.builder()
                .id(1L)
                .title("Test Email")
                .message("Test message")
                .recipientEmail("test@example.com")
                .recipient("test-user")
                .type(NotificationType.EMAIL)
                .build();

        // Act & Assert
        assertThrows(PermanentSendException.class, () -> emailSender.send(notification));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
