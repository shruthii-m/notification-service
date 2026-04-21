package com.notification.notification.load;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Watches consumer-group lag on notification.send during a load test.
 * Logs total lag every second and reports time-to-drain once the producer
 * stops. Exits when lag reaches 0 or PROBE_TIMEOUT_SECONDS elapses.
 *
 * Env vars (with defaults):
 *   KAFKA_BOOTSTRAP=localhost:9092
 *   TOPIC=notification.send
 *   CONSUMER_GROUP=notification-sender-group
 *   PROBE_TIMEOUT_SECONDS=300
 */
public class KafkaLoadProbe {

    private static final String KAFKA_BOOTSTRAP = env("KAFKA_BOOTSTRAP", "localhost:9092");
    private static final String TOPIC = env("TOPIC", "notification.send");
    private static final String CONSUMER_GROUP = env("CONSUMER_GROUP", "notification-sender-group");
    private static final int PROBE_TIMEOUT_SECONDS = envInt("PROBE_TIMEOUT_SECONDS", 300);

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP);

        System.out.println("Probing consumer-group lag:");
        System.out.println("  topic=" + TOPIC);
        System.out.println("  group=" + CONSUMER_GROUP);
        System.out.println("  timeout=" + PROBE_TIMEOUT_SECONDS + "s");
        System.out.println();

        try (AdminClient admin = AdminClient.create(props)) {
            Instant start = Instant.now();
            long peakLag = 0;
            long peakLagWallMs = -1;       // wall-clock when peak was observed
            int zeroLagTicks = 0;
            boolean sawActivity = false;   // becomes true once we observe lag > 0

            while (Duration.between(start, Instant.now()).toSeconds() < PROBE_TIMEOUT_SECONDS) {
                long lag = currentLag(admin);
                long nowMs = System.currentTimeMillis();
                if (lag > peakLag) {
                    peakLag = lag;
                    peakLagWallMs = nowMs;
                }
                if (lag > 0) {
                    sawActivity = true;
                }
                long elapsed = Duration.between(start, Instant.now()).toSeconds();
                System.out.printf("t=%3ds  lag=%d%n", elapsed, lag);

                // Only consider the run "drained" after we've observed at least some lag.
                if (sawActivity && lag == 0) {
                    zeroLagTicks++;
                    if (zeroLagTicks >= 3) {
                        // Time-to-drain: from peak lag observation until lag first hit 0
                        // (i.e., 3 zero ticks ago).
                        long firstZeroWallMs = nowMs - 2_000L;
                        long drainMs = peakLagWallMs > 0 ? firstZeroWallMs - peakLagWallMs : 0;
                        System.out.println();
                        System.out.println("Drain complete.");
                        System.out.println("  Peak lag:                " + peakLag);
                        System.out.println("  Time-to-drain (peak→0):  " + drainMs + " ms");
                        return;
                    }
                } else {
                    zeroLagTicks = 0;
                }

                Thread.sleep(1000);
            }

            System.out.println();
            System.out.println("Probe timed out after " + PROBE_TIMEOUT_SECONDS + "s. Peak lag: " + peakLag);
        }
    }

    private static long currentLag(AdminClient admin) throws Exception {
        ListConsumerGroupOffsetsResult committedResult = admin.listConsumerGroupOffsets(CONSUMER_GROUP);
        Map<TopicPartition, OffsetAndMetadata> committed =
            committedResult.partitionsToOffsetAndMetadata().get();

        Map<TopicPartition, OffsetSpec> endSpecs = new HashMap<>();
        for (TopicPartition tp : committed.keySet()) {
            if (tp.topic().equals(TOPIC)) {
                endSpecs.put(tp, OffsetSpec.latest());
            }
        }
        if (endSpecs.isEmpty()) {
            return 0;
        }

        ListOffsetsResult endResult = admin.listOffsets(endSpecs);
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = endResult.all().get();

        long totalLag = 0;
        for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> e : ends.entrySet()) {
            OffsetAndMetadata c = committed.get(e.getKey());
            long committedOffset = c == null ? 0 : c.offset();
            totalLag += Math.max(0, e.getValue().offset() - committedOffset);
        }
        return totalLag;
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
