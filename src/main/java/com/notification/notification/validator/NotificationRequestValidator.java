package com.notification.notification.validator;

import com.notification.notification.dto.NotificationRequest;
import com.notification.notification.entity.NotificationType;
import com.notification.notification.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotificationRequestValidator {

    private final EmailValidator emailValidator = EmailValidator.getInstance();

    /**
     * Validates a notification request based on its type.
     *
     * @param request the notification request DTO
     * @throws BadRequestException if validation fails
     */
    public void validate(NotificationRequest request) {
        if (request.getType() == null) {
            throw new BadRequestException("Notification type is required");
        }

        switch (request.getType()) {
            case EMAIL -> validateEmailNotification(request);
            case SMS -> validateSmsNotification(request);
            case PUSH -> validatePushNotification(request);
            case IN_APP -> validateInAppNotification(request);
            default -> throw new BadRequestException("Unsupported notification type: " + request.getType());
        }
    }

    private void validateEmailNotification(NotificationRequest request) {
        if (request.getRecipientEmail() == null || request.getRecipientEmail().isBlank()) {
            throw new BadRequestException("Recipient email is required for EMAIL notifications");
        }

        if (!emailValidator.isValid(request.getRecipientEmail())) {
            throw new BadRequestException("Invalid email address: " + request.getRecipientEmail());
        }

        log.debug("Email notification validated successfully: {}", request.getRecipientEmail());
    }

    private void validateSmsNotification(NotificationRequest request) {
        log.debug("SMS notification validation - currently no specific validation required");
    }

    private void validatePushNotification(NotificationRequest request) {
        log.debug("Push notification validation - currently no specific validation required");
    }

    private void validateInAppNotification(NotificationRequest request) {
        log.debug("In-app notification validation - currently no specific validation required");
    }
}
