package com.notification.notification.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.notification.dto.NotificationRequest;
import com.notification.notification.entity.NotificationType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom load test runner for the notification microservice.
 * <p>
 * Sends concurrent HTTP requests to the notification API and measures:
 * - Total requests sent
 * - Success/failure count
 * - Response times (avg, p95, max)
 * - Throughput (requests/second)
 * <p>
 * Usage:
 * <pre>
 * ./gradlew testClasses
 * java -cp build/classes/java/test:build/libs/* \\
 *      com.notification.notification.load.LoadTestRunner
 * </pre>
 */
public class LoadTestRunner {

    private static final String BASE_URL = "http://localhost:8081";
    private static final String ENDPOINT = "/api/v1/notifications";

    // Load test configuration
    private static final int TOTAL_REQUESTS = 1000;
    private static final int CONCURRENT_THREADS = 50;
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    // Metrics
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failureCount = new AtomicInteger(0);
    private static final AtomicLong totalResponseTime = new AtomicLong(0);
    private static final List<Long> responseTimes = new CopyOnWriteArrayList<>();

    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("Notification Microservice Load Test");
        System.out.println("=".repeat(80));
        System.out.println("Configuration:");
        System.out.println("  Base URL: " + BASE_URL);
        System.out.println("  Endpoint: " + ENDPOINT);
        System.out.println("  Total Requests: " + TOTAL_REQUESTS);
        System.out.println("  Concurrent Threads: " + CONCURRENT_THREADS);
        System.out.println("  Timeout: " + REQUEST_TIMEOUT_SECONDS + "s");
        System.out.println("=".repeat(80));
        System.out.println();

        try {
            runLoadTest();
        } catch (Exception e) {
            System.err.println("Load test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runLoadTest() throws Exception {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .build();

        ObjectMapper objectMapper = new ObjectMapper();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch latch = new CountDownLatch(TOTAL_REQUESTS);

        Instant startTime = Instant.now();

        // Submit all requests
        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            final int requestId = i;
            executor.submit(() -> {
                try {
                    sendNotificationRequest(httpClient, objectMapper, requestId);
                } catch (Exception e) {
                    System.err.println("Request " + requestId + " failed: " + e.getMessage());
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();

                    // Print progress every 100 requests
                    int completed = TOTAL_REQUESTS - (int) latch.getCount();
                    if (completed % 100 == 0) {
                        System.out.printf("Progress: %d/%d requests completed%n", completed, TOTAL_REQUESTS);
                    }
                }
            });
        }

        // Wait for all requests to complete
        System.out.println("Waiting for all requests to complete...");
        latch.await();

        Instant endTime = Instant.now();
        long durationMs = Duration.between(startTime, endTime).toMillis();

        // Shutdown executor
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Print results
        printResults(durationMs);
    }

    private static void sendNotificationRequest(HttpClient httpClient, ObjectMapper objectMapper, int requestId)
            throws Exception {
        // Generate unique notification request
        NotificationRequest request = generateNotificationRequest(requestId);

        // Serialize to JSON
        String requestBody = objectMapper.writeValueAsString(request);

        // Build HTTP request
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + ENDPOINT))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        // Measure response time
        long startTime = System.currentTimeMillis();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        long endTime = System.currentTimeMillis();
        long responseTime = endTime - startTime;

        // Record metrics
        responseTimes.add(responseTime);
        totalResponseTime.addAndGet(responseTime);

        // Check response status
        if (response.statusCode() == 202 || response.statusCode() == 200) {
            successCount.incrementAndGet();
        } else {
            failureCount.incrementAndGet();
            System.err.println("Request " + requestId + " returned status " + response.statusCode());
        }
    }

    private static NotificationRequest generateNotificationRequest(int requestId) {
        Random random = new Random(requestId);
        int orgId = random.nextInt(10) + 1; // 10 organizations
        int userId = random.nextInt(1000) + 1; // 1000 users

        return NotificationRequest.builder()
                .title("Load Test Notification " + requestId)
                .message("This is a load test message #" + requestId)
                .recipient("user" + userId)
                .recipientEmail("user" + userId + "@loadtest.com")
                .type(NotificationType.EMAIL)
                .organizationId("org-" + orgId)
                .build();
    }

    private static void printResults(long durationMs) {
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("Load Test Results");
        System.out.println("=".repeat(80));

        // Basic metrics
        double durationSec = durationMs / 1000.0;
        double throughput = TOTAL_REQUESTS / durationSec;

        System.out.println("Summary:");
        System.out.println("  Total Requests: " + TOTAL_REQUESTS);
        System.out.println("  Successful: " + successCount.get());
        System.out.println("  Failed: " + failureCount.get());
        System.out.println("  Success Rate: " + String.format("%.2f%%", (successCount.get() * 100.0 / TOTAL_REQUESTS)));
        System.out.println();

        System.out.println("Performance:");
        System.out.println("  Total Duration: " + String.format("%.2f", durationSec) + "s");
        System.out.println("  Throughput: " + String.format("%.2f", throughput) + " req/s");
        System.out.println();

        // Response time statistics
        long avgResponseTime = 0;
        if (!responseTimes.isEmpty()) {
            Collections.sort(responseTimes);

            avgResponseTime = totalResponseTime.get() / responseTimes.size();
            long minResponseTime = responseTimes.get(0);
            long maxResponseTime = responseTimes.get(responseTimes.size() - 1);

            int p50Index = (int) (responseTimes.size() * 0.50);
            int p95Index = (int) (responseTimes.size() * 0.95);
            int p99Index = (int) (responseTimes.size() * 0.99);

            long p50 = responseTimes.get(p50Index);
            long p95 = responseTimes.get(p95Index);
            long p99 = responseTimes.get(p99Index);

            System.out.println("Response Time (ms):");
            System.out.println("  Average: " + avgResponseTime + "ms");
            System.out.println("  Min: " + minResponseTime + "ms");
            System.out.println("  Max: " + maxResponseTime + "ms");
            System.out.println("  P50 (Median): " + p50 + "ms");
            System.out.println("  P95: " + p95 + "ms");
            System.out.println("  P99: " + p99 + "ms");
        }

        System.out.println("=".repeat(80));

        // Performance assessment
        System.out.println();
        System.out.println("Assessment:");
        if (throughput >= 1000 && avgResponseTime < 50) {
            System.out.println("  ✓ EXCELLENT: High throughput (>1000 req/s) and low latency (<50ms)");
        } else if (throughput >= 500 && avgResponseTime < 100) {
            System.out.println("  ✓ GOOD: Acceptable throughput (>500 req/s) and latency (<100ms)");
        } else if (throughput >= 100) {
            System.out.println("  ⚠ FAIR: Moderate throughput (>100 req/s)");
        } else {
            System.out.println("  ✗ POOR: Low throughput (<100 req/s) - optimization needed");
        }

        if (failureCount.get() > 0) {
            System.out.println("  ⚠ WARNING: " + failureCount.get() + " requests failed");
        }
    }
}
