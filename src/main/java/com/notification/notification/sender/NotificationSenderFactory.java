package com.notification.notification.sender;

import com.notification.notification.entity.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class NotificationSenderFactory {

    private final Map<NotificationType, NotificationSender> senderMap;

    public NotificationSenderFactory(List<NotificationSender> senders) {
        this.senderMap = senders.stream()
                .collect(Collectors.toMap(
                        NotificationSender::getSupportedType,
                        Function.identity()
                ));

        log.info("Initialized NotificationSenderFactory with {} senders: {}",
                senderMap.size(), senderMap.keySet());
    }

    /**
     * Gets the appropriate sender for the given notification type.
     *
     * @param type the notification type
     * @return Optional containing the sender if available
     */
    public Optional<NotificationSender> getSender(NotificationType type) {
        NotificationSender sender = senderMap.get(type);

        if (sender == null) {
            log.warn("No sender found for notification type: {}", type);
            return Optional.empty();
        }

        if (!sender.isAvailable()) {
            log.warn("Sender for type {} is not available", type);
            return Optional.empty();
        }

        return Optional.of(sender);
    }

    /**
     * Checks if a sender is available for the given notification type.
     *
     * @param type the notification type
     * @return true if a sender is available and configured
     */
    public boolean hasSender(NotificationType type) {
        return getSender(type).isPresent();
    }
}
