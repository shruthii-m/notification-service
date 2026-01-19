package com.notification.notification.exception;

public class PermanentSendException extends NotificationSendException {

    public PermanentSendException(String message) {
        super(message, false);
    }

    public PermanentSendException(String message, Throwable cause) {
        super(message, cause, false);
    }
}
