package com.notification.notification.sender;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendResult {

    /**
     * Indicates if the send operation was successful.
     */
    private boolean success;

    /**
     * Provider-specific message ID or tracking ID.
     */
    private String providerId;

    /**
     * Error message if the send operation failed.
     */
    private String errorMessage;

    /**
     * Indicates if the error is retryable (transient failure).
     */
    private boolean retryable;

    /**
     * Timestamp when the notification was sent.
     */
    private LocalDateTime sentAt;

    /**
     * Creates a successful send result.
     *
     * @param providerId the provider message ID
     * @param sentAt     the timestamp when sent
     * @return successful SendResult
     */
    public static SendResult success(String providerId, LocalDateTime sentAt) {
        return SendResult.builder()
                .success(true)
                .providerId(providerId)
                .sentAt(sentAt)
                .build();
    }

    /**
     * Creates a failed send result.
     *
     * @param errorMessage the error message
     * @param retryable    whether the error is retryable
     * @return failed SendResult
     */
    public static SendResult failure(String errorMessage, boolean retryable) {
        return SendResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .retryable(retryable)
                .build();
    }
}
