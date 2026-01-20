package com.notification.notification.sender;

import com.notification.notification.entity.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationSenderFactoryTest {

    @Mock
    private NotificationSender emailSender;

    @Mock
    private NotificationSender smsSender;

    private NotificationSenderFactory factory;

    @BeforeEach
    void setUp() {
        lenient().when(emailSender.getSupportedType()).thenReturn(NotificationType.EMAIL);
        lenient().when(emailSender.isAvailable()).thenReturn(true);

        lenient().when(smsSender.getSupportedType()).thenReturn(NotificationType.SMS);
        lenient().when(smsSender.isAvailable()).thenReturn(true);

        factory = new NotificationSenderFactory(List.of(emailSender, smsSender));
    }

    @Test
    void testGetSenderForEmail() {
        // Act
        Optional<NotificationSender> sender = factory.getSender(NotificationType.EMAIL);

        // Assert
        assertTrue(sender.isPresent());
        assertEquals(emailSender, sender.get());
    }

    @Test
    void testGetSenderForSms() {
        // Act
        Optional<NotificationSender> sender = factory.getSender(NotificationType.SMS);

        // Assert
        assertTrue(sender.isPresent());
        assertEquals(smsSender, sender.get());
    }

    @Test
    void testGetSenderForUnsupportedType() {
        // Act
        Optional<NotificationSender> sender = factory.getSender(NotificationType.PUSH);

        // Assert
        assertFalse(sender.isPresent());
    }

    @Test
    void testGetSenderWhenNotAvailable() {
        // Arrange
        lenient().when(emailSender.isAvailable()).thenReturn(false);

        // Act
        Optional<NotificationSender> sender = factory.getSender(NotificationType.EMAIL);

        // Assert
        assertFalse(sender.isPresent());
    }

    @Test
    void testHasSenderForEmail() {
        // Act & Assert
        assertTrue(factory.hasSender(NotificationType.EMAIL));
    }

    @Test
    void testHasSenderForUnsupportedType() {
        // Act & Assert
        assertFalse(factory.hasSender(NotificationType.PUSH));
    }
}
