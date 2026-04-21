#!/usr/bin/env bash
# Kafka-direct load test runner.
#
# Prereqs:
#   1. docker compose up -d           (kafka, postgres)
#   2. SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun
#      (so the NoOpSender is active and SMTP is skipped)
#
# Usage:
#   ./scripts/run-kafka-load-test.sh [TARGET_RATE] [DURATION_SECONDS]
#
# Examples:
#   ./scripts/run-kafka-load-test.sh            # 1500 msg/sec for 60 s
#   ./scripts/run-kafka-load-test.sh 2000 30    # 2000 msg/sec for 30 s
#
# Writes a timestamped result artifact under load-test-results/.

set -euo pipefail

TARGET_RATE="${1:-1500}"
DURATION_SECONDS="${2:-60}"

cd "$(dirname "$0")/.."

RESULT_DIR="load-test-results"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RESULT_FILE="${RESULT_DIR}/run-${TIMESTAMP}.log"
mkdir -p "${RESULT_DIR}"

echo "==> Verifying docker compose services..."
if ! docker ps --format '{{.Names}}' | grep -q notification-kafka; then
  echo "ERROR: notification-kafka container not running. Run 'docker compose up -d' first." >&2
  exit 1
fi

echo "==> Verifying app is running with loadtest profile..."
# Try actuator/health first, fall back to TCP probe on 8081 if actuator isn't enabled.
if ! curl -sf http://localhost:8081/actuator/health >/dev/null 2>&1; then
  if ! nc -z localhost 8081 >/dev/null 2>&1; then
    echo "ERROR: app not responding on :8081. Start it with:" >&2
    echo "  SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun" >&2
    exit 1
  fi
  echo "    (actuator not available, but port 8081 is listening)"
fi

export TARGET_RATE DURATION_SECONDS

echo "==> Starting lag probe in background..."
./gradlew --console=plain -q kafkaLoadProbe > "${RESULT_DIR}/probe-${TIMESTAMP}.log" 2>&1 &
PROBE_PID=$!

# Give the probe a second to attach.
sleep 2

echo "==> Running Kafka-direct load test: ${TARGET_RATE} msg/sec for ${DURATION_SECONDS}s"
./gradlew --console=plain -q kafkaLoadTest 2>&1 | tee "${RESULT_FILE}"

echo "==> Waiting for probe to finish draining..."
wait "${PROBE_PID}" || true

echo ""
echo "==> Results:"
echo "    runner log:  ${RESULT_FILE}"
echo "    probe log:   ${RESULT_DIR}/probe-${TIMESTAMP}.log"
