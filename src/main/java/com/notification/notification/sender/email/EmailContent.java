package com.notification.notification.sender.email;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailContent {

    /**
     * Recipient email address.
     */
    private String to;

    /**
     * Email subject line.
     */
    private String subject;

    /**
     * HTML content of the email (preferred).
     */
    private String htmlContent;

    /**
     * Plain text content of the email (fallback).
     */
    private String plainTextContent;

    /**
     * Sender email address.
     */
    private String from;

    /**
     * Sender name.
     */
    private String fromName;
}
