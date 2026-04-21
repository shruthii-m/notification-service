package com.notification.notification.load;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.notification.notification.entity.NotificationType;
import com.notification.notification.messaging.dto.NotificationMessage;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka-direct load test. Pre-inserts notification rows via JDBC, then
 * publishes matching messages to notification.send at a target rate so
 * the full Kafka + consumer + DB + sender pipeline is exercised.
 *
 * Pair with KafkaLoadProbe to observe consumer lag during the run.
 *
 * Env vars (with defaults):
 *   TARGET_RATE=1500          messages per second
 *   DURATION_SECONDS=60       how long to sustain the rate
 *   NUM_ORGS=10               spread load across this many org ids
 *   KAFKA_BOOTSTRAP=localhost:9092
 *   POSTGRES_URL=jdbc:postgresql://localhost:5433/notification
 *   POSTGRES_USER=notification
 *   POSTGRES_PASSWORD=12345
 *   TOPIC=notification.send
 */
public class KafkaLoadTestRunner {

    private static final int TARGET_RATE = envInt("TARGET_RATE", 1500);
    private static final int DURATION_SECONDS = envInt("DURATION_SECONDS", 60);
    private static final int NUM_ORGS = envInt("NUM_ORGS", 10);
    private static final int TOTAL_MESSAGES = TARGET_RATE * DURATION_SECONDS;
    private static final int INSERT_BATCH_SIZE = 500;

    private static final String KAFKA_BOOTSTRAP = env("KAFKA_BOOTSTRAP", "localhost:9092");
    private static final String TOPIC = env("TOPIC", "notification.send");
    private static final String PG_URL = env("POSTGRES_URL", "jdbc:postgresql://localhost:5433/notification");
    private static final String PG_USER = env("POSTGRES_USER", "notification");
    private static final String PG_PASSWORD = env("POSTGRES_PASSWORD", "12345");

    private static final AtomicInteger sent = new AtomicInteger(0);
    private static final AtomicInteger failed = new AtomicInteger(0);
    private static final AtomicLong totalAckLatencyNanos = new AtomicLong(0);
    private static final List<Long> ackLatencyMillis = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        banner();
        List<UUID> uuids = insertRows();
        publish(uuids);
    }

    private static void banner() {
        System.out.println("=".repeat(80));
        System.out.println("Kafka Direct Load Test");
        System.out.println("=".repeat(80));
        System.out.println("  Target rate:     " + TARGET_RATE + " msg/sec");
        System.out.println("  Duration:        " + DURATION_SECONDS + "s");
        System.out.println("  Total messages:  " + TOTAL_MESSAGES);
        System.out.println("  Topic:           " + TOPIC);
        System.out.println("  Kafka:           " + KAFKA_BOOTSTRAP);
        System.out.println("  Postgres:        " + PG_URL);
        System.out.println("=".repeat(80));
    }

    private static List<UUID> insertRows() throws Exception {
        System.out.println("Pre-inserting " + TOTAL_MESSAGES + " notification rows...");
        Instant start = Instant.now();
        List<UUID> uuids = new ArrayList<>(TOTAL_MESSAGES);

        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD)) {
            conn.setAutoCommit(false);
            String sequenceName = findIdSequence(conn);
            System.out.println("Using id sequence: " + sequenceName);

            String sql = "INSERT INTO notifications " +
                "(id, uuid, organization_id, title, message, recipient, recipient_email, " +
                " type, status, retry_count, max_retries, created_at) " +
                "VALUES (nextval('" + sequenceName + "'), ?, ?, ?, ?, ?, ?, ?, ?, 0, 5, ?)";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                Timestamp now = Timestamp.valueOf(LocalDateTime.now());
                for (int i = 0; i < TOTAL_MESSAGES; i++) {
                    UUID uuid = UUID.randomUUID();
                    uuids.add(uuid);
                    ps.setObject(1, uuid);
                    ps.setString(2, "org-" + (i % NUM_ORGS));
                    ps.setString(3, "Kafka load test " + i);
                    ps.setString(4, "benchmark payload " + i);
                    ps.setString(5, "user-" + i);
                    ps.setString(6, "user-" + i + "@loadtest.local");
                    ps.setString(7, NotificationType.EMAIL.name());
                    ps.setString(8, "PENDING");
                    ps.setTimestamp(9, now);
                    ps.addBatch();

                    if ((i + 1) % INSERT_BATCH_SIZE == 0) {
                        ps.executeBatch();
                        conn.commit();
                    }
                }
                ps.executeBatch();
                conn.commit();
            }
        }

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        System.out.printf("Inserted %d rows in %d ms (%.0f rows/sec)%n",
            TOTAL_MESSAGES, elapsedMs, TOTAL_MESSAGES / (elapsedMs / 1000.0));
        return uuids;
    }

    /**
     * The `id` column is populated via a Hibernate-managed sequence (the entity uses
     * GenerationType.AUTO on a Long, which on Postgres resolves to a sequence).
     * Discover the sequence name at runtime instead of hard-coding it.
     */
    private static String findIdSequence(Connection conn) throws Exception {
        // Preferred: ask Postgres which sequence drives the `id` column.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_get_serial_sequence('notifications','id')")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String s = rs.getString(1);
                    if (s != null && !s.isBlank()) {
                        return s;
                    }
                }
            }
        }
        // Fallback: take the only notification-related sequence in the public schema.
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT sequence_schema || '.' || sequence_name FROM information_schema.sequences " +
                "WHERE sequence_schema='public' AND sequence_name ILIKE '%notification%' " +
                "ORDER BY sequence_name LIMIT 1")) {
            if (rs.next()) {
                return rs.getString(1);
            }
        }
        throw new IllegalStateException("Could not find id sequence for table 'notifications'. " +
            "Start the app once so Hibernate creates the schema, then re-run.");
    }

    private static void publish(List<UUID> uuids) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            System.out.printf("Publishing %d messages at %d msg/sec...%n", TOTAL_MESSAGES, TARGET_RATE);

            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            long intervalNanos = 1_000_000_000L / TARGET_RATE;
            Instant startInstant = Instant.now();

            for (int i = 0; i < TOTAL_MESSAGES; i++) {
                final int idx = i;
                long delayNanos = intervalNanos * i;
                scheduler.schedule(() -> sendOne(producer, mapper, uuids.get(idx), idx),
                    delayNanos, TimeUnit.NANOSECONDS);
            }

            scheduler.shutdown();
            boolean finished = scheduler.awaitTermination(DURATION_SECONDS + 30L, TimeUnit.SECONDS);
            producer.flush();

            Instant endInstant = Instant.now();
            double elapsedSec = Duration.between(startInstant, endInstant).toMillis() / 1000.0;
            printSummary(elapsedSec, finished);
        }
    }

    private static void sendOne(KafkaProducer<String, String> producer,
                                ObjectMapper mapper, UUID uuid, int idx) {
        NotificationMessage msg = NotificationMessage.builder()
            .notificationUuid(uuid)
            .organizationId("org-" + (idx % NUM_ORGS))
            .title("Kafka load test " + idx)
            .message("benchmark payload " + idx)
            .recipient("user-" + idx)
            .recipientEmail("user-" + idx + "@loadtest.local")
            .type(NotificationType.EMAIL)
            .retryCount(0)
            .maxRetries(5)
            .createdAt(LocalDateTime.now())
            .correlationId(uuid.toString())
            .build();

        try {
            byte[] payload = mapper.writeValueAsBytes(msg);
            String value = new String(payload, StandardCharsets.UTF_8);
            ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, msg.getOrganizationId(), value);
            long publishStart = System.nanoTime();
            producer.send(record, (RecordMetadata m, Exception e) -> {
                long ackLatencyNanos = System.nanoTime() - publishStart;
                if (e != null) {
                    failed.incrementAndGet();
                } else {
                    sent.incrementAndGet();
                    totalAckLatencyNanos.addAndGet(ackLatencyNanos);
                    ackLatencyMillis.add(ackLatencyNanos / 1_000_000L);
                }
            });
        } catch (Exception e) {
            failed.incrementAndGet();
        }
    }

    private static void printSummary(double elapsedSec, boolean schedulerFinished) {
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("Kafka Direct Load Test Results");
        System.out.println("=".repeat(80));
        int sentCount = sent.get();
        int failedCount = failed.get();
        double achievedRate = sentCount / elapsedSec;

        System.out.printf("  Elapsed:         %.2f s%n", elapsedSec);
        System.out.printf("  Sent (acked):    %d%n", sentCount);
        System.out.printf("  Failed:          %d%n", failedCount);
        System.out.printf("  Achieved rate:   %.0f msg/sec (target %d)%n", achievedRate, TARGET_RATE);
        System.out.printf("  Scheduler done:  %s%n", schedulerFinished);

        if (!ackLatencyMillis.isEmpty()) {
            List<Long> sorted = new ArrayList<>(ackLatencyMillis);
            sorted.sort(Long::compareTo);
            long p50 = sorted.get((int) (sorted.size() * 0.50));
            long p95 = sorted.get((int) (sorted.size() * 0.95));
            long p99 = sorted.get((int) (sorted.size() * 0.99));
            long avg = totalAckLatencyNanos.get() / sentCount / 1_000_000L;
            System.out.println();
            System.out.println("  Producer-ack latency (ms):");
            System.out.printf("    avg=%d p50=%d p95=%d p99=%d%n", avg, p50, p95, p99);
        }
        System.out.println("=".repeat(80));

        if (achievedRate >= TARGET_RATE * 0.95 && failedCount == 0) {
            System.out.println("  PASS: sustained target rate");
        } else {
            System.out.println("  REVIEW: rate below target or failures present");
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static int envInt(String key, int fallback) {
        try {
            return Integer.parseInt(env(key, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
