#!/bin/bash

# Kafka Consumer Lag Monitoring Script
# Monitors consumer group lag for the notification microservice
#
# Usage: ./monitor-kafka-lag.sh [interval_seconds]
# Default interval: 5 seconds
#
# Requirements:
# - Docker must be running
# - notification-kafka container must be running

INTERVAL=${1:-5}
KAFKA_CONTAINER="notification-kafka"
BOOTSTRAP_SERVER="localhost:9092"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Consumer groups to monitor
CONSUMER_GROUPS=(
    "notification-sender-group"
    "notification-retry-group"
)

echo "========================================="
echo "Kafka Consumer Lag Monitor"
echo "========================================="
echo "Interval: ${INTERVAL}s"
echo "Kafka Container: ${KAFKA_CONTAINER}"
echo "Consumer Groups: ${CONSUMER_GROUPS[@]}"
echo "========================================="
echo ""

# Check if Kafka container is running
if ! docker ps | grep -q ${KAFKA_CONTAINER}; then
    echo -e "${RED}ERROR: Kafka container '${KAFKA_CONTAINER}' is not running${NC}"
    echo "Start it with: docker compose up -d kafka"
    exit 1
fi

# Function to display consumer group lag
show_consumer_lag() {
    local group=$1

    echo -e "\n${GREEN}=== Consumer Group: ${group} ===${NC}"

    # Get consumer group description
    RESULT=$(docker exec ${KAFKA_CONTAINER} kafka-consumer-groups.sh \
        --bootstrap-server ${BOOTSTRAP_SERVER} \
        --group ${group} \
        --describe 2>&1)

    # Check if group exists
    if echo "$RESULT" | grep -q "Consumer group.*does not exist"; then
        echo -e "${YELLOW}  Group does not exist yet (no consumers connected)${NC}"
        return
    fi

    # Parse and display results
    echo "$RESULT" | grep -v "GROUP" | while read line; do
        if [[ -n "$line" ]]; then
            LAG=$(echo "$line" | awk '{print $(NF-1)}')

            # Color code based on lag
            if [[ "$LAG" =~ ^[0-9]+$ ]]; then
                if [ "$LAG" -lt 100 ]; then
                    echo -e "  ${GREEN}$line${NC}"
                elif [ "$LAG" -lt 1000 ]; then
                    echo -e "  ${YELLOW}$line${NC}"
                else
                    echo -e "  ${RED}$line${NC}"
                fi
            else
                echo "  $line"
            fi
        fi
    done
}

# Function to show topic stats
show_topic_stats() {
    echo -e "\n${GREEN}=== Topic Statistics ===${NC}"

    docker exec ${KAFKA_CONTAINER} kafka-topics.sh \
        --bootstrap-server ${BOOTSTRAP_SERVER} \
        --list | grep "notification" | while read topic; do

        PARTITION_COUNT=$(docker exec ${KAFKA_CONTAINER} kafka-topics.sh \
            --bootstrap-server ${BOOTSTRAP_SERVER} \
            --describe \
            --topic ${topic} 2>/dev/null | grep "PartitionCount" | awk '{print $4}')

        echo "  ${topic}: ${PARTITION_COUNT} partitions"
    done
}

# Main monitoring loop
while true; do
    clear
    echo "========================================="
    echo "Kafka Consumer Lag Monitor"
    echo "Time: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "========================================="

    # Show lag for each consumer group
    for group in "${CONSUMER_GROUPS[@]}"; do
        show_consumer_lag "$group"
    done

    # Show topic statistics
    show_topic_stats

    # Wait for next iteration
    echo ""
    echo "Refreshing in ${INTERVAL}s... (Ctrl+C to exit)"
    sleep ${INTERVAL}
done
