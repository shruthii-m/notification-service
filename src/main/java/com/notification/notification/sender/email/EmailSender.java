package com.notification.notification.sender.email;

import com.notification.notification.sender.NotificationSender;

public interface EmailSender extends NotificationSender {

    /**
     * Converts notification entity to email-specific content.
     *
     * @param notification the notification entity
     * @return EmailContent DTO
     */
    default EmailContent buildEmailContent(com.notification.notification.entity.Notification notification) {
        return EmailContent.builder()
                .to(notification.getRecipientEmail())
                .subject(notification.getTitle())
                .htmlContent(buildHtmlContent(notification))
                .plainTextContent(notification.getMessage())
                .build();
    }

    /**
     * Builds HTML content from the notification message.
     * Can be overridden for custom HTML templates.
     *
     * @param notification the notification entity
     * @return HTML content
     */
    default String buildHtmlContent(com.notification.notification.entity.Notification notification) {
        return String.format(
                "<html><body><h2>%s</h2><p>%s</p></body></html>",
                notification.getTitle(),
                notification.getMessage().replace("\n", "<br>")
        );
    }
}
