# Load Testing Guide

This document describes how to run load tests for the notification microservice.

## Overview

The load test (`LoadTestRunner.java`) sends concurrent HTTP requests to the notification API and measures:
- **Total requests sent**
- **Success/failure count**
- **Response times** (average, p50, p95, p99, min, max)
- **Throughput** (requests per second)
- **Success rate**

## Configuration

Default configuration (can be modified in `LoadTestRunner.java`):
- **Total Requests**: 1,000
- **Concurrent Threads**: 50
- **Request Timeout**: 30 seconds
- **Target URL**: http://localhost:8081

## Prerequisites

Before running load tests, ensure:

1. **Application is running**:
   ```bash
   ./gradlew bootRun
   ```

2. **Dependencies are running**:
   ```bash
   docker compose up -d
   ```
   This starts:
   - Kafka (port 9092)
   - Kafka UI (port 9081)
   - PostgreSQL (port 5433)

3. **Application is healthy**:
   ```bash
   curl http://localhost:8081/actuator/health
   ```

## Running Load Tests

### Method 1: Using Gradle Task (Recommended)

```bash
./gradlew loadTest
```

This is the easiest method and automatically checks dependencies.

### Method 2: Using Shell Script

```bash
./run-load-test.sh
```

The script will:
- Check if the application is running
- Build test classes if needed
- Run the load test
- Display results

### Method 3: Direct Java Execution

```bash
# First, compile test classes
./gradlew testClasses

# Run the load test
java -cp "build/classes/java/main:build/classes/java/test:$(./gradlew -q printTestRuntimeClasspath)" \
    com.notification.notification.load.LoadTestRunner
```

### Method 4: Using JUnit (Optional)

The load test can also be run as a JUnit test, but it's disabled by default:

```bash
# Remove @Disabled annotation from LoadTest.java, then run:
./gradlew test --tests LoadTest
```

## Understanding Results

### Sample Output

```
================================================================================
Notification Microservice Load Test
================================================================================
Configuration:
  Base URL: http://localhost:8081
  Endpoint: /api/v1/notifications
  Total Requests: 1000
  Concurrent Threads: 50
  Timeout: 30s
================================================================================

Waiting for all requests to complete...
Progress: 100/1000 requests completed
Progress: 200/1000 requests completed
...
Progress: 1000/1000 requests completed

================================================================================
Load Test Results
================================================================================
Summary:
  Total Requests: 1000
  Successful: 998
  Failed: 2
  Success Rate: 99.80%

Performance:
  Total Duration: 2.45s
  Throughput: 408.16 req/s

Response Time (ms):
  Average: 97ms
  Min: 15ms
  Max: 450ms
  P50 (Median): 85ms
  P95: 180ms
  P99: 320ms
================================================================================

Assessment:
  ✓ GOOD: Acceptable throughput (>500 req/s) and latency (<100ms)
```

### Performance Criteria

The load test provides automatic assessment:

| Performance Level | Criteria |
|------------------|----------|
| **EXCELLENT** | Throughput >1000 req/s AND avg latency <50ms |
| **GOOD** | Throughput >500 req/s AND avg latency <100ms |
| **FAIR** | Throughput >100 req/s |
| **POOR** | Throughput <100 req/s |

### Key Metrics

- **Throughput**: Number of requests processed per second
  - Higher is better
  - Expected: >100 req/s for basic workloads, >500 req/s for production-ready systems

- **Average Response Time**: Mean time to receive a response
  - Lower is better
  - Expected: <100ms for async API (returns 202 Accepted immediately)

- **P95/P99**: 95th and 99th percentile response times
  - Shows worst-case performance for most requests
  - P95 <200ms and P99 <500ms are typical targets

- **Success Rate**: Percentage of requests that returned 2xx status codes
  - Should be >99% for stable systems
  - Lower rates indicate errors or timeouts

## Customizing Load Tests

To modify the load test configuration, edit `LoadTestRunner.java`:

```java
// Line 40-42: Adjust these constants
private static final int TOTAL_REQUESTS = 1000;        // Total number of requests
private static final int CONCURRENT_THREADS = 50;       // Concurrent threads
private static final int REQUEST_TIMEOUT_SECONDS = 30;  // Timeout per request
```

Example configurations:

### Light Load Test
```java
private static final int TOTAL_REQUESTS = 100;
private static final int CONCURRENT_THREADS = 10;
```

### Heavy Load Test
```java
private static final int TOTAL_REQUESTS = 10000;
private static final int CONCURRENT_THREADS = 200;
```

### Stress Test
```java
private static final int TOTAL_REQUESTS = 50000;
private static final int CONCURRENT_THREADS = 500;
```

## Monitoring During Load Tests

### Kafka Monitoring

1. Open Kafka UI: http://localhost:9081
2. Navigate to Topics → `notification.send`
3. Monitor:
   - Message throughput
   - Consumer lag (should stay low)
   - Partition distribution

### Application Logs

Watch application logs in real-time:
```bash
# If running with gradlew bootRun
tail -f build/spring-boot-run.log

# If running with Docker
docker logs -f notification-app
```

### Database Load

Monitor PostgreSQL connections:
```bash
PGPASSWORD=12345 psql -h localhost -p 5433 -U notification -d notification \
  -c "SELECT count(*) FROM notifications;"
```

## Troubleshooting

### Application Not Running
```
ERROR: Application is not running on port 8081
```
**Solution**: Start the application with `./gradlew bootRun`

### Connection Timeout
```
Request 42 failed: java.net.http.HttpTimeoutException
```
**Solutions**:
- Reduce `CONCURRENT_THREADS` (less concurrent load)
- Increase `REQUEST_TIMEOUT_SECONDS`
- Check if application is overwhelmed (monitor CPU/memory)

### High Failure Rate
```
Success Rate: 75.00%
```
**Solutions**:
- Check application logs for errors
- Verify Kafka and PostgreSQL are running
- Reduce load to find breaking point
- Check system resources (CPU, memory, disk I/O)

### Kafka Consumer Lag
If Kafka consumer lag is high during load tests:
1. Check consumer logs for errors
2. Verify all Kafka partitions are being consumed
3. Consider increasing consumer concurrency (currently 3)
4. Monitor retry topic for stuck messages

## Performance Benchmarks

Expected performance on typical development machine (4 cores, 8GB RAM):

| Metric | Expected Value |
|--------|----------------|
| Throughput | 300-800 req/s |
| Average Response Time | 50-150ms |
| P95 Response Time | 100-300ms |
| Success Rate | >99% |

Production systems with proper resources should achieve:
- Throughput: >1000 req/s
- Average Response Time: <50ms
- P95 Response Time: <100ms
- Success Rate: >99.9%

## Best Practices

1. **Always run load tests in a dedicated environment** - Don't run against production
2. **Start with small loads** - Begin with 100 requests, then gradually increase
3. **Monitor system resources** - Watch CPU, memory, and disk I/O
4. **Isolate bottlenecks** - Test different components separately (API, Kafka, DB)
5. **Document results** - Keep a record of performance over time
6. **Test realistic scenarios** - Use realistic data distributions (multiple orgs, users)

## Advanced: Custom Load Profiles

For more complex scenarios, consider:

1. **Ramp-up testing**: Gradually increase load
2. **Sustained load**: Run at constant rate for extended period
3. **Spike testing**: Sudden bursts of high traffic
4. **Stress testing**: Find the breaking point

These can be implemented by modifying the `LoadTestRunner` class or using dedicated tools like JMeter, Gatling, or K6.

## See Also

- [README.md](README.md) - General project documentation
- [CLAUDE.md](CLAUDE.md) - Development guidelines
- Kafka UI: http://localhost:9081
- Application Health: http://localhost:8081/actuator/health
