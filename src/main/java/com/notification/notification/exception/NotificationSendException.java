package com.notification.notification.exception;

public class NotificationSendException extends RuntimeException {

    private final boolean retryable;

    public NotificationSendException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public NotificationSendException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
