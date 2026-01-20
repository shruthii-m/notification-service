#!/bin/bash
# Load Test Runner Script for Notification Microservice
# This script runs the load test against the running application

set -e

echo "========================================"
echo "Notification Microservice Load Test"
echo "========================================"
echo ""

# Check if application is running
echo "Checking if application is running on port 8081..."
if ! curl -s http://localhost:8081/actuator/health > /dev/null 2>&1; then
    echo "ERROR: Application is not running on port 8081"
    echo "Please start the application first:"
    echo "  ./gradlew bootRun"
    echo "Or with Docker:"
    echo "  docker compose up -d"
    exit 1
fi

echo "✓ Application is running"
echo ""

# Build test classes if needed
echo "Building test classes..."
./gradlew testClasses --console=plain --quiet

echo ""
echo "Starting load test..."
echo "Press Ctrl+C to stop"
echo ""

# Run the load test
java -cp "build/classes/java/main:build/classes/java/test:$(./gradlew -q printTestRuntimeClasspath)" \
    com.notification.notification.load.LoadTestRunner

echo ""
echo "Load test completed!"
