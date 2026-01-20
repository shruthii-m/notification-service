package com.notification.notification.dto;

import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.entity.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.hateoas.RepresentationModel;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class NotificationResponse extends RepresentationModel<NotificationResponse> {

    private Long id;
    private UUID uuid;
    private String organizationId;
    private String title;
    private String message;
    private String recipient;
    private String recipientEmail;
    private NotificationType type;
    private NotificationStatus status;
    private Integer retryCount;
    private Integer maxRetries;
    private String errorCode;
    private String errorMessage;
    private String providerId;
    private String providerName;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
    private LocalDateTime readAt;
}

