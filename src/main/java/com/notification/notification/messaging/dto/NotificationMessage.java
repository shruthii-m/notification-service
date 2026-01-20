package com.notification.notification.messaging.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.notification.notification.entity.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage {

    @JsonProperty("notificationUuid")
    private UUID notificationUuid;

    @JsonProperty("notificationId")
    private Long notificationId;

    @JsonProperty("organizationId")
    private String organizationId;

    @JsonProperty("title")
    private String title;

    @JsonProperty("message")
    private String message;

    @JsonProperty("recipient")
    private String recipient;

    @JsonProperty("recipientEmail")
    private String recipientEmail;

    @JsonProperty("type")
    private NotificationType type;

    @JsonProperty("retryCount")
    @Builder.Default
    private Integer retryCount = 0;

    @JsonProperty("maxRetries")
    @Builder.Default
    private Integer maxRetries = 5;

    @JsonProperty("createdAt")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonProperty("correlationId")
    private String correlationId;
}
