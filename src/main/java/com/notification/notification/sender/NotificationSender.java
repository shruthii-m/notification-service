package com.notification.notification.sender;

import com.notification.notification.entity.Notification;
import com.notification.notification.entity.NotificationType;

public interface NotificationSender {

    /**
     * Sends a notification using the provider implementation.
     *
     * @param notification the notification to send
     * @return SendResult containing the result of the send operation
     */
    SendResult send(Notification notification);

    /**
     * Returns the notification type this sender supports.
     *
     * @return the supported NotificationType
     */
    NotificationType getSupportedType();

    /**
     * Checks if the sender is available and properly configured.
     *
     * @return true if the sender is available, false otherwise
     */
    boolean isAvailable();
}
