package com.notification.notification.exception;

public class TransientSendException extends NotificationSendException {

    public TransientSendException(String message) {
        super(message, true);
    }

    public TransientSendException(String message, Throwable cause) {
        super(message, cause, true);
    }
}
