package com.notification.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaTopicConfig {

    @Value("${kafka.topic.notification-requested}")
    private String notificationRequestedTopic;

    @Value("${kafka.topic.notification-send}")
    private String notificationSendTopic;

    @Value("${kafka.topic.notification-send-retry}")
    private String notificationSendRetryTopic;

    @Value("${kafka.topic.notification-send-dlq}")
    private String notificationSendDlqTopic;

    @Value("${kafka.topic.notification-events}")
    private String notificationEventsTopic;

    @Value("${kafka.topic.notification-requested.partitions}")
    private int requestedPartitions;

    @Value("${kafka.topic.notification-send.partitions}")
    private int sendPartitions;

    @Value("${kafka.topic.notification-send-retry.partitions}")
    private int retryPartitions;

    @Value("${kafka.topic.notification-send-dlq.partitions}")
    private int dlqPartitions;

    @Value("${kafka.topic.notification-events.partitions}")
    private int eventsPartitions;

    @Bean
    public NewTopic notificationRequestedTopic() {
        return TopicBuilder
            .name(notificationRequestedTopic)
            .partitions(requestedPartitions)
            .replicas(1)
            .build();
    }

    @Bean
    public NewTopic notificationSendTopic() {
        return TopicBuilder
            .name(notificationSendTopic)
            .partitions(sendPartitions)
            .replicas(1)
            .build();
    }

    @Bean
    public NewTopic notificationSendRetryTopic() {
        return TopicBuilder
            .name(notificationSendRetryTopic)
            .partitions(retryPartitions)
            .replicas(1)
            .build();
    }

    @Bean
    public NewTopic notificationSendDlqTopic() {
        return TopicBuilder
            .name(notificationSendDlqTopic)
            .partitions(dlqPartitions)
            .replicas(1)
            .build();
    }

    @Bean
    public NewTopic notificationEventsTopic() {
        Map<String, String> configs = new HashMap<>();
        configs.put("retention.ms", "2592000000"); // 30 days retention

        return TopicBuilder
            .name(notificationEventsTopic)
            .partitions(eventsPartitions)
            .replicas(1)
            .configs(configs)
            .build();
    }
}
