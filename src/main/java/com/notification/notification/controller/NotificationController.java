package com.notification.notification.controller;

import com.notification.notification.dto.ApiResponse;
import com.notification.notification.dto.NotificationRequest;
import com.notification.notification.dto.NotificationResponse;
import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.entity.NotificationType;
import com.notification.notification.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.CollectionModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.*;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public ResponseEntity<ApiResponse<NotificationResponse>> createNotification(
            @Valid @RequestBody NotificationRequest request) {
        log.info("REST request to create notification");
        NotificationResponse response = notificationService.createNotification(request);
        addSelfLink(response);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(response, "Notification accepted for processing"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<NotificationResponse>> getNotificationById(@PathVariable Long id) {
        log.info("REST request to get notification by ID: {}", id);
        NotificationResponse response = notificationService.getNotificationById(id);
        addSelfLink(response);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getAllNotifications() {
        log.info("REST request to get all notifications");
        List<NotificationResponse> notifications = notificationService.getAllNotifications();
        notifications.forEach(this::addSelfLink);
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    @GetMapping("/paginated")
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> getAllNotificationsPaginated(
            @PageableDefault(size = 10, sort = "createdAt") Pageable pageable) {
        log.info("REST request to get all notifications with pagination");
        Page<NotificationResponse> notifications = notificationService.getAllNotifications(pageable);
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    @GetMapping("/recipient/{recipient}")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getNotificationsByRecipient(
            @PathVariable String recipient) {
        log.info("REST request to get notifications for recipient: {}", recipient);
        List<NotificationResponse> notifications = notificationService.getNotificationsByRecipient(recipient);
        notifications.forEach(this::addSelfLink);
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    @GetMapping("/recipient/{recipient}/paginated")
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> getNotificationsByRecipientPaginated(
            @PathVariable String recipient,
            @PageableDefault(size = 10, sort = "createdAt") Pageable pageable) {
        log.info("REST request to get notifications for recipient: {} with pagination", recipient);
        Page<NotificationResponse> notifications = notificationService.getNotificationsByRecipient(recipient, pageable);
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getNotificationsByStatus(
            @PathVariable NotificationStatus status) {
        log.info("REST request to get notifications by status: {}", status);
        List<NotificationResponse> notifications = notificationService.getNotificationsByStatus(status);
        notifications.forEach(this::addSelfLink);
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getNotificationsByType(
            @PathVariable NotificationType type) {
        log.info("REST request to get notifications by type: {}", type);
        List<NotificationResponse> notifications = notificationService.getNotificationsByType(type);
        notifications.forEach(this::addSelfLink);
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<NotificationResponse>> updateNotification(
            @PathVariable Long id,
            @Valid @RequestBody NotificationRequest request) {
        log.info("REST request to update notification: {}", id);
        NotificationResponse response = notificationService.updateNotification(id, request);
        addSelfLink(response);
        return ResponseEntity.ok(ApiResponse.success(response, "Notification updated successfully"));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<NotificationResponse>> updateNotificationStatus(
            @PathVariable Long id,
            @RequestParam NotificationStatus status) {
        log.info("REST request to update notification status: {} to {}", id, status);
        NotificationResponse response = notificationService.updateNotificationStatus(id, status);
        addSelfLink(response);
        return ResponseEntity.ok(ApiResponse.success(response, "Notification status updated successfully"));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(@PathVariable Long id) {
        log.info("REST request to mark notification as read: {}", id);
        NotificationResponse response = notificationService.markAsRead(id);
        addSelfLink(response);
        return ResponseEntity.ok(ApiResponse.success(response, "Notification marked as read"));
    }

    @PatchMapping("/{id}/send")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsSent(@PathVariable Long id) {
        log.info("REST request to mark notification as sent: {}", id);
        NotificationResponse response = notificationService.markAsSent(id);
        addSelfLink(response);
        return ResponseEntity.ok(ApiResponse.success(response, "Notification marked as sent"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteNotification(@PathVariable Long id) {
        log.info("REST request to delete notification: {}", id);
        notificationService.deleteNotification(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Notification deleted successfully"));
    }

    @GetMapping("/recipient/{recipient}/unread/count")
    public ResponseEntity<ApiResponse<Long>> countUnreadNotifications(@PathVariable String recipient) {
        log.info("REST request to count unread notifications for: {}", recipient);
        long count = notificationService.countUnreadNotifications(recipient);
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    private void addSelfLink(NotificationResponse response) {
        response.add(linkTo(methodOn(NotificationController.class)
                .getNotificationById(response.getId())).withSelfRel());
    }
}

