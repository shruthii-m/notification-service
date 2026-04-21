package com.notification.notification.sender;

import com.notification.notification.entity.Notification;
import com.notification.notification.entity.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * No-op EMAIL sender used only under the `loadtest` Spring profile.
 * Isolates the Kafka + DB pipeline from the SMTP provider so
 * benchmark numbers reflect the service, not the mail server.
 */
@Slf4j
@Component
@Profile("loadtest")
public class NoOpSender implements NotificationSender {

    @Override
    public SendResult send(Notification notification) {
        return SendResult.success("noop-" + notification.getUuid(), LocalDateTime.now());
    }

    @Override
    public NotificationType getSupportedType() {
        return NotificationType.EMAIL;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
