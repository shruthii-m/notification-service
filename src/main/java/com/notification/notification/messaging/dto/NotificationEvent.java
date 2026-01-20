package com.notification.notification.messaging.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {

    @JsonProperty("notificationUuid")
    private UUID notificationUuid;

    @JsonProperty("organizationId")
    private String organizationId;

    @JsonProperty("eventType")
    private EventType eventType;

    @JsonProperty("details")
    private Map<String, Object> details;

    @JsonProperty("timestamp")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    public enum EventType {
        CREATED,
        SEND_ATTEMPTED,
        SENT,
        FAILED,
        RETRIED
    }
}
