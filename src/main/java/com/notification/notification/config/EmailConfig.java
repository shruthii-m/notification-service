package com.notification.notification.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "notification.email")
public class EmailConfig {

    private boolean enabled = true;
    private String defaultFromEmail;
    private String defaultFromName;

    // JavaMailSender is auto-configured by Spring Boot
    // based on spring.mail.* properties in application.properties
}
