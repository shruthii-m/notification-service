package com.notification.notification.sender.email;

import com.notification.notification.entity.Notification;
import com.notification.notification.entity.NotificationType;
import com.notification.notification.exception.PermanentSendException;
import com.notification.notification.exception.TransientSendException;
import com.notification.notification.sender.SendResult;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;

    @Value("${notification.email.default-from-email}")
    private String defaultFromEmail;

    @Value("${notification.email.default-from-name}")
    private String defaultFromName;

    @Value("${notification.email.enabled:true}")
    private boolean enabled;

    @Override
    public SendResult send(Notification notification) {
        if (!isAvailable()) {
            log.error("SMTP email sender is not available");
            throw new PermanentSendException("Email sender is not properly configured");
        }

        if (notification.getRecipientEmail() == null || notification.getRecipientEmail().isBlank()) {
            log.error("Recipient email is missing for notification {}", notification.getId());
            throw new PermanentSendException("Recipient email is required for email notifications");
        }

        try {
            EmailContent emailContent = buildEmailContent(notification);
            emailContent.setFrom(defaultFromEmail);
            emailContent.setFromName(defaultFromName);

            sendEmail(emailContent);

            log.info("Email sent successfully via SMTP to {}", emailContent.getTo());
            return SendResult.success(notification.getId().toString(), LocalDateTime.now());

        } catch (MailAuthenticationException e) {
            log.error("SMTP authentication failed: {}", e.getMessage());
            throw new PermanentSendException("Email authentication failed. Check SMTP credentials.", e);
        } catch (MailSendException e) {
            log.error("Failed to send email: {}", e.getMessage());
            if (isTransientError(e)) {
                throw new TransientSendException("Temporary email sending failure: " + e.getMessage(), e);
            } else {
                throw new PermanentSendException("Email sending failed: " + e.getMessage(), e);
            }
        } catch (MailException e) {
            log.error("Mail error: {}", e.getMessage());
            throw new TransientSendException("Email service error: " + e.getMessage(), e);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Message creation error: {}", e.getMessage());
            throw new PermanentSendException("Failed to create email message: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error sending email for notification {}: {}",
                    notification.getId(), e.getMessage(), e);
            throw new TransientSendException("Failed to send email: " + e.getMessage(), e);
        }
    }

    private void sendEmail(EmailContent emailContent) throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(emailContent.getFrom(), emailContent.getFromName());
        helper.setTo(emailContent.getTo());
        helper.setSubject(emailContent.getSubject());

        // Set HTML content as primary, plain text as fallback
        if (emailContent.getHtmlContent() != null && !emailContent.getHtmlContent().isBlank()) {
            helper.setText(emailContent.getPlainTextContent(), emailContent.getHtmlContent());
        } else {
            helper.setText(emailContent.getPlainTextContent(), false);
        }

        mailSender.send(message);
    }

    private boolean isTransientError(MailSendException e) {
        // Network issues, timeouts, temporary server problems are transient
        String message = e.getMessage().toLowerCase();
        return message.contains("timeout") ||
               message.contains("connection") ||
               message.contains("network") ||
               message.contains("temporarily") ||
               message.contains("try again");
    }

    @Override
    public NotificationType getSupportedType() {
        return NotificationType.EMAIL;
    }

    @Override
    public boolean isAvailable() {
        return enabled && mailSender != null;
    }
}
