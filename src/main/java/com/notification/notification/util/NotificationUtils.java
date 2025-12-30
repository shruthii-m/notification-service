package com.notification.notification.util;

import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.entity.NotificationType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class NotificationUtils {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private NotificationUtils() {
        // Private constructor to prevent instantiation
    }

    public static String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DATE_FORMATTER);
    }

    public static boolean isValidNotificationType(String type) {
        try {
            NotificationType.valueOf(type.toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isValidNotificationStatus(String status) {
        try {
            NotificationStatus.valueOf(status.toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean canTransitionStatus(NotificationStatus from, NotificationStatus to) {
        return switch (from) {
            case PENDING -> to == NotificationStatus.SENT || to == NotificationStatus.FAILED;
            case SENT -> to == NotificationStatus.DELIVERED || to == NotificationStatus.FAILED;
            case DELIVERED -> to == NotificationStatus.READ;
            case READ, FAILED -> false;
        };
    }

    public static String truncateMessage(String message, int maxLength) {
        if (message == null || message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength - 3) + "...";
    }
}

