package com.notification.notification.load;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit wrapper for the load test.
 * <p>
 * This test is disabled by default since load tests should not run on every build.
 * <p>
 * To run the load test:
 * <pre>
 * # Option 1: Using Gradle task (recommended)
 * ./gradlew loadTest
 *
 * # Option 2: Using shell script
 * ./run-load-test.sh
 *
 * # Option 3: Enable this test and run
 * # Remove @Disabled annotation and run:
 * ./gradlew test --tests LoadTest
 * </pre>
 * <p>
 * Requirements:
 * - Application must be running on http://localhost:8081
 * - Kafka must be running (for async processing)
 * - PostgreSQL must be running (for persistence)
 */
@Disabled("Load test disabled by default - run manually with './gradlew loadTest'")
@DisplayName("Load Tests")
public class LoadTest {

    @Test
    @DisplayName("Run load test with 1000 requests and 50 concurrent threads")
    void runLoadTest() throws Exception {
        // This will run the main method of LoadTestRunner
        LoadTestRunner.main(new String[]{});
    }
}
