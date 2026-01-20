package com.notification.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.notification.dto.NotificationRequest;
import com.notification.notification.dto.NotificationResponse;
import com.notification.notification.entity.NotificationStatus;
import com.notification.notification.entity.NotificationType;
import com.notification.notification.exception.ResourceNotFoundException;
import com.notification.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationController.class)
@DisplayName("NotificationController Integration Tests")
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NotificationService notificationService;

    private NotificationRequest testRequest;
    private NotificationResponse testResponse;

    @BeforeEach
    void setUp() {
        testRequest = NotificationRequest.builder()
                .title("Test Notification")
                .message("Test message")
                .recipient("user123")
                .recipientEmail("test@example.com")
                .type(NotificationType.EMAIL)
                .organizationId("org-123")
                .build();

        testResponse = NotificationResponse.builder()
                .id(1L)
                .uuid(UUID.randomUUID())
                .organizationId("org-123")
                .title("Test Notification")
                .message("Test message")
                .recipient("user123")
                .recipientEmail("test@example.com")
                .type(NotificationType.EMAIL)
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .maxRetries(5)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ==================== CREATE TESTS ====================

    @Test
    @DisplayName("POST /api/v1/notifications should return 202 ACCEPTED")
    void testCreateNotification_Returns202() throws Exception {
        // Arrange
        when(notificationService.createNotification(any(NotificationRequest.class)))
                .thenReturn(testResponse);

        // Act & Assert
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.title").value("Test Notification"))
                .andExpect(jsonPath("$.message").value("Notification accepted for processing"));

        verify(notificationService).createNotification(any(NotificationRequest.class));
    }

    @Test
    @DisplayName("POST /api/v1/notifications with invalid data should return 400")
    void testCreateNotification_InvalidData_Returns400() throws Exception {
        // Arrange - Request with missing required fields
        NotificationRequest invalidRequest = NotificationRequest.builder().build();

        // Act & Assert
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        verify(notificationService, never()).createNotification(any());
    }

    // ==================== GET BY ID TESTS ====================

    @Test
    @DisplayName("GET /api/v1/notifications/{id} should return notification")
    void testGetNotificationById_Success() throws Exception {
        // Arrange
        when(notificationService.getNotificationById(1L)).thenReturn(testResponse);

        // Act & Assert
        mockMvc.perform(get("/api/v1/notifications/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.title").value("Test Notification"));

        verify(notificationService).getNotificationById(1L);
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{id} with non-existent id should return 404")
    void testGetNotificationById_NotFound() throws Exception {
        // Arrange
        when(notificationService.getNotificationById(999L))
                .thenThrow(new ResourceNotFoundException("Notification", "id", 999L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/notifications/999"))
                .andExpect(status().isNotFound());

        verify(notificationService).getNotificationById(999L);
    }

    // ==================== GET ALL TESTS ====================

    @Test
    @DisplayName("GET /api/v1/notifications should return all notifications")
    void testGetAllNotifications() throws Exception {
        // Arrange
        NotificationResponse response2 = NotificationResponse.builder()
                .id(2L)
                .uuid(UUID.randomUUID())
                .title("Second Notification")
                .message("Second message")
                .recipient("user456")
                .recipientEmail("test2@example.com")
                .type(NotificationType.SMS)
                .status(NotificationStatus.SENT)
                .createdAt(LocalDateTime.now())
                .build();

        List<NotificationResponse> responses = Arrays.asList(testResponse, response2);
        when(notificationService.getAllNotifications()).thenReturn(responses);

        // Act & Assert
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[1].id").value(2));

        verify(notificationService).getAllNotifications();
    }

    @Test
    @DisplayName("GET /api/v1/notifications/paginated should return paginated notifications")
    void testGetAllNotifications_Paginated() throws Exception {
        // Arrange
        Page<NotificationResponse> page = new PageImpl<>(
                Arrays.asList(testResponse),
                PageRequest.of(0, 10),
                1
        );
        when(notificationService.getAllNotifications(any())).thenReturn(page);

        // Act & Assert
        mockMvc.perform(get("/api/v1/notifications/paginated")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(notificationService).getAllNotifications(any());
    }

    // ==================== FILTER TESTS ====================

    @Test
    @DisplayName("GET /api/v1/notifications/recipient/{recipient} should filter by recipient")
    void testGetNotificationsByRecipient() throws Exception {
        // Arrange
        when(notificationService.getNotificationsByRecipient("user123"))
                .thenReturn(Arrays.asList(testResponse));

        // Act & Assert
        mockMvc.perform(get("/api/v1/notifications/recipient/user123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].recipient").value("user123"));

        verify(notificationService).getNotificationsByRecipient("user123");
    }

    @Test
    @DisplayName("GET /api/v1/notifications/status/{status} should filter by status")
    void testGetNotificationsByStatus() throws Exception {
        // Arrange
        when(notificationService.getNotificationsByStatus(NotificationStatus.PENDING))
                .thenReturn(Arrays.asList(testResponse));

        // Act & Assert
        mockMvc.perform(get("/api/v1/notifications/status/PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));

        verify(notificationService).getNotificationsByStatus(NotificationStatus.PENDING);
    }

    @Test
    @DisplayName("GET /api/v1/notifications/type/{type} should filter by type")
    void testGetNotificationsByType() throws Exception {
        // Arrange
        when(notificationService.getNotificationsByType(NotificationType.EMAIL))
                .thenReturn(Arrays.asList(testResponse));

        // Act & Assert
        mockMvc.perform(get("/api/v1/notifications/type/EMAIL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].type").value("EMAIL"));

        verify(notificationService).getNotificationsByType(NotificationType.EMAIL);
    }

    // ==================== UPDATE TESTS ====================

    @Test
    @DisplayName("PUT /api/v1/notifications/{id} should update notification")
    void testUpdateNotification() throws Exception {
        // Arrange
        NotificationResponse updatedResponse = NotificationResponse.builder()
                .id(1L)
                .uuid(testResponse.getUuid())
                .title("Updated Title")
                .message("Updated message")
                .recipient("user123")
                .recipientEmail("test@example.com")
                .type(NotificationType.EMAIL)
                .status(NotificationStatus.PENDING)
                .createdAt(testResponse.getCreatedAt())
                .build();

        when(notificationService.updateNotification(eq(1L), any(NotificationRequest.class)))
                .thenReturn(updatedResponse);

        // Act & Assert
        mockMvc.perform(put("/api/v1/notifications/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.message").value("Notification updated successfully"));

        verify(notificationService).updateNotification(eq(1L), any(NotificationRequest.class));
    }

    // ==================== STATUS UPDATE TESTS ====================

    @Test
    @DisplayName("PATCH /api/v1/notifications/{id}/status should update status")
    void testUpdateNotificationStatus() throws Exception {
        // Arrange
        testResponse.setStatus(NotificationStatus.SENT);
        when(notificationService.updateNotificationStatus(1L, NotificationStatus.SENT))
                .thenReturn(testResponse);

        // Act & Assert
        mockMvc.perform(patch("/api/v1/notifications/1/status")
                        .param("status", "SENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.message").value("Notification status updated successfully"));

        verify(notificationService).updateNotificationStatus(1L, NotificationStatus.SENT);
    }

    @Test
    @DisplayName("PATCH /api/v1/notifications/{id}/read should mark as read")
    void testMarkAsRead() throws Exception {
        // Arrange
        testResponse.setStatus(NotificationStatus.READ);
        testResponse.setReadAt(LocalDateTime.now());
        when(notificationService.markAsRead(1L)).thenReturn(testResponse);

        // Act & Assert
        mockMvc.perform(patch("/api/v1/notifications/1/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("READ"))
                .andExpect(jsonPath("$.message").value("Notification marked as read"));

        verify(notificationService).markAsRead(1L);
    }

    @Test
    @DisplayName("PATCH /api/v1/notifications/{id}/send should mark as sent")
    void testMarkAsSent() throws Exception {
        // Arrange
        testResponse.setStatus(NotificationStatus.SENT);
        testResponse.setSentAt(LocalDateTime.now());
        when(notificationService.markAsSent(1L)).thenReturn(testResponse);

        // Act & Assert
        mockMvc.perform(patch("/api/v1/notifications/1/send"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.message").value("Notification marked as sent"));

        verify(notificationService).markAsSent(1L);
    }

    // ==================== DELETE TESTS ====================

    @Test
    @DisplayName("DELETE /api/v1/notifications/{id} should delete notification")
    void testDeleteNotification() throws Exception {
        // Arrange
        doNothing().when(notificationService).deleteNotification(1L);

        // Act & Assert
        mockMvc.perform(delete("/api/v1/notifications/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Notification deleted successfully"));

        verify(notificationService).deleteNotification(1L);
    }

    @Test
    @DisplayName("DELETE /api/v1/notifications/{id} with non-existent id should return 404")
    void testDeleteNotification_NotFound() throws Exception {
        // Arrange
        doThrow(new ResourceNotFoundException("Notification", "id", 999L))
                .when(notificationService).deleteNotification(999L);

        // Act & Assert
        mockMvc.perform(delete("/api/v1/notifications/999"))
                .andExpect(status().isNotFound());

        verify(notificationService).deleteNotification(999L);
    }

    // ==================== COUNT TESTS ====================

    @Test
    @DisplayName("GET /api/v1/notifications/recipient/{recipient}/unread/count should return count")
    void testCountUnreadNotifications() throws Exception {
        // Arrange
        when(notificationService.countUnreadNotifications("user123")).thenReturn(5L);

        // Act & Assert
        mockMvc.perform(get("/api/v1/notifications/recipient/user123/unread/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(5));

        verify(notificationService).countUnreadNotifications("user123");
    }
}
