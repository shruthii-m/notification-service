package com.notification.notification.integration;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Abstract base class for integration tests using Testcontainers.
 * Provides shared PostgreSQL and Kafka containers for all integration tests.
 *
 * All integration test classes should extend this class to get access to:
 * - PostgreSQL database (via Testcontainers)
 * - Kafka broker (via Testcontainers)
 * - Test profile configuration
 *
 * Containers are started once per test class and reused across test methods.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /**
     * PostgreSQL container using latest version.
     * Database name: test, Username: test, Password: test
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:latest")
    )
    .withDatabaseName("test")
    .withUsername("test")
    .withPassword("test")
    .withReuse(true);

    /**
     * Kafka container using Confluent Platform 7.5.0 (KRaft mode, no Zookeeper).
     */
    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    )
    .withReuse(true);

    /**
     * Dynamically configure Spring properties based on Testcontainers.
     * Overrides application-test.properties with actual container URLs.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL configuration
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Kafka configuration
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.consumer.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.producer.bootstrap-servers", kafka::getBootstrapServers);
    }

    /**
     * Ensure containers are started before any tests run.
     * This method can be overridden by subclasses for additional setup.
     */
    @BeforeAll
    static void beforeAll() {
        // Containers are already started by @Container annotation
        // This method is available for subclasses to override if needed
    }
}
