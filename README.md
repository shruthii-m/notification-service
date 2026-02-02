# Notification Service – Multi-Tenant Event-Driven Microservice

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.8.1-black.svg)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Latest-blue.svg)](https://www.postgresql.org/)

## 📋 Table of Contents
- [Overview](#overview)
- [Current Implementation Status](#current-implementation-status)
- [Quick Start](#quick-start)
- [Architecture](#architecture)
- [Configuration](#configuration)
- [API Usage](#api-usage)
- [Monitoring](#monitoring)
- [Design Document](#design-document)

---

## 🎯 Overview

The **Notification Service** is a production-ready, multi-tenant, event-driven microservice for processing and delivering notifications (Email, SMS, WhatsApp, Push) asynchronously across multiple organizations.

### Key Features

* ✅ **Asynchronous Processing** - Non-blocking API with Apache Kafka
* ✅ **Multi-tenant** - Organization-based isolation
* ✅ **Fault-tolerant** - Automatic retries with exponential backoff
* ✅ **Scalable** - Kafka partitioning and consumer concurrency
* ✅ **Auditable** - Full event sourcing and audit trail
* ✅ **Idempotent** - UUID-based deduplication
* ✅ **Production-ready** - Kafka, PostgreSQL, Docker Compose

---

## 🚀 Current Implementation Status

### ✅ Implemented Features

- [x] **REST API** - Create, read, update, delete notifications
- [x] **Apache Kafka Integration** - Full async event-driven architecture
- [x] **Email Provider** - SMTP/Gmail integration
- [x] **Retry Mechanism** - 5-level exponential backoff (5s, 30s, 2m, 10m, 30m)
- [x] **Dead Letter Queue** - Permanent failure handling
- [x] **Event Sourcing** - Audit trail via `notification.events` topic
- [x] **Multi-tenancy Support** - Organization ID partitioning
- [x] **Idempotency** - UUID-based deduplication
- [x] **Database Persistence** - PostgreSQL with JPA/Hibernate
- [x] **Docker Compose** - Kafka, PostgreSQL, Redis, Kafka UI
- [x] **Monitoring** - Kafka UI dashboard

### 🚧 Planned Features

- [ ] SMS Provider (Twilio)
- [ ] WhatsApp Provider
- [ ] Push Notification Provider (FCM)
- [ ] Authentication & Authorization (JWT)
- [ ] Template Management
- [ ] Rate Limiting per Organization
- [ ] Metrics & Observability (Prometheus/Grafana)
- [ ] Database Migration (Flyway/Liquibase)

---

## 🏃 Quick Start

### Prerequisites

- Java 21+
- Docker & Docker Compose
- PostgreSQL (running on port 5433)
- Gradle 8.5+

### 1. Clone & Setup

```bash
cd notification
```

### 2. Configure Environment Variables

Create a `.env` file in the project root:

```env
# Gmail SMTP Configuration (for email notifications)
GMAIL_USERNAME=your.email@gmail.com
GMAIL_APP_PASSWORD=your-16-character-app-password

# Optional: Custom configuration
# KAFKA_BOOTSTRAP_SERVERS=localhost:9092
# POSTGRES_URL=jdbc:postgresql://localhost:5433/notification
```

> **Note**: To get a Gmail App Password:
> 1. Go to Google Account Settings → Security
> 2. Enable 2-Step Verification
> 3. Generate App Password for "Mail"

### 3. Start Infrastructure

```bash
# Start Kafka, Kafka UI, and Redis
docker compose up -d

# Verify containers are running
docker ps
```

Expected output:
```
notification-kafka       Up      0.0.0.0:9092->9092/tcp
notification-kafka-ui    Up      0.0.0.0:9081->8080/tcp
notification-redis-1     Up      6379/tcp
```

### 4. Start Application

```bash
# Build and run
./gradlew bootRun

# Or build JAR and run
./gradlew build
java -jar build/libs/notification-0.0.1-SNAPSHOT.jar
```

Application will start on `http://localhost:8081`

### 5. Send a Test Notification

```bash
curl -X POST http://localhost:8081/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "organizationId": "org-123",
    "title": "Welcome!",
    "message": "Thank you for signing up",
    "recipient": "user123",
    "recipientEmail": "test@example.com",
    "type": "EMAIL"
  }'
```

Response (202 Accepted):
```json
{
  "success": true,
  "message": "Notification accepted for processing",
  "data": {
    "id": 1,
    "uuid": "034f2285-9214-4fad-b038-1c4076e2b3a3",
    "status": "PENDING",
    "organizationId": "org-123"
  }
}
```

---

## 🏗️ Architecture

### Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| **Language** | Java | 21 |
| **Framework** | Spring Boot | 3.3.5 |
| **Message Broker** | Apache Kafka | 3.8.1 |
| **Database** | PostgreSQL | Latest |
| **Cache** | Redis | Latest |
| **Build Tool** | Gradle | 8.5 |
| **Containerization** | Docker Compose | - |

### Event-Driven Architecture with Apache Kafka

```
┌─────────────────┐
│   REST API      │  POST /api/v1/notifications
│  (202 Accepted) │  Returns immediately with PENDING status
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│              Apache Kafka Producer                       │
│  - Publishes to notification.send topic                 │
│  - Partition key: organizationId                        │
│  - Idempotent producer (enable.idempotence=true)       │
└────────┬────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│         Kafka Topic: notification.send (6 partitions)   │
└────────┬────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│         SenderConsumer (3 concurrent consumers)         │
│  1. Fetch notification from DB by UUID                  │
│  2. Idempotency check (skip if already SENT)           │
│  3. Update status: PENDING → PROCESSING                │
│  4. Attempt to send via provider (SMTP/Twilio/FCM)     │
└────────┬────────────────────────────────────────────────┘
         │
    ┌────┴────┐
    │         │
  Success  Failure
    │         │
    ▼         ▼
┌──────┐  ┌────────────────────────┐
│ SENT │  │   Transient/Permanent  │
└──┬───┘  └───┬───────────────┬────┘
   │          │               │
   │      Transient       Permanent
   │          │               │
   ▼          ▼               ▼
Update    Retry Queue      DLQ Topic
 DB      (with backoff)   (notification.send.dlq)
   │          │
   │    ┌─────┴──────┐
   │    │ RetryConsumer│
   │    │ (checks delay)│
   │    └─────┬────────┘
   │          │
   └──────────┴─────> Publish Audit Event
                     (notification.events topic)
```

---

### Why Apache Kafka?

After evaluating RabbitMQ vs Kafka, **Kafka was chosen** for:

| Feature | Benefit |
|---------|---------|
| **Event Sourcing** | Full audit trail via immutable event log |
| **Event Replay** | Reprocess notifications from any point in time |
| **Scalability** | Horizontal scaling via partitions (10K-100K msgs/sec) |
| **Real-time Analytics** | Kafka Streams for delivery metrics |
| **Long-term Retention** | 30-day retention for `notification.events` |
| **Multi-tenancy** | Partition by organizationId for isolation |

---

### Kafka Topics

| Topic Name | Partitions | Retention | Purpose |
|------------|------------|-----------|---------|
| `notification.send` | 6 | 7 days | Main notification processing queue |
| `notification.send.retry` | 3 | 7 days | Failed notifications awaiting retry |
| `notification.send.dlq` | 1 | 30 days | Permanent failures for investigation |
| `notification.events` | 6 | 30 days | Audit trail (event sourcing) |

**Partitioning Strategy:**
- `notification.send`: Partitioned by `organizationId` (ensures per-org ordering)
- `notification.events`: Partitioned by `organizationId` (audit isolation)

---

### Kafka Consumer Groups

| Consumer Group | Concurrency | Topics | Purpose |
|----------------|-------------|--------|---------|
| `notification-sender-group` | 3 | `notification.send` | Processes and sends notifications |
| `notification-retry-group` | 3 | `notification.send.retry` | Handles delayed retries |

---

### Message Format

**NotificationMessage (Kafka Payload):**
```json
{
  "notificationUuid": "034f2285-9214-4fad-b038-1c4076e2b3a3",
  "notificationId": 352,
  "organizationId": "org-123",
  "title": "Welcome!",
  "message": "Thank you for signing up",
  "recipient": "user123",
  "recipientEmail": "test@example.com",
  "type": "EMAIL",
  "retryCount": 0,
  "maxRetries": 5,
  "createdAt": "2026-01-19T17:00:20.812943751",
  "correlationId": "034f2285-9214-4fad-b038-1c4076e2b3a3"
}
```

**Kafka Message Headers:**
| Header | Value | Purpose |
|--------|-------|---------|
| `X-Correlation-Id` | UUID | Request tracing |
| `X-Organization-Id` | org-123 | Multi-tenancy |
| `X-Retry-Count` | 2 | Current retry attempt |
| `X-Next-Retry-Timestamp` | 1768870577974 | When to retry (epoch ms) |

---

### Retry Strategy (Exponential Backoff)

| Retry Level | Delay | Total Time |
|-------------|-------|------------|
| 1 | 5 seconds | 5s |
| 2 | 30 seconds | 35s |
| 3 | 2 minutes | 2m 35s |
| 4 | 10 minutes | 12m 35s |
| 5 | 30 minutes | 42m 35s |

After 5 retries → **notification.send.dlq** (Dead Letter Queue)

**Retry Flow:**
```
1. SenderConsumer catches TransientSendException
2. Increment retryCount in DB (PROCESSING → RETRYING)
3. Calculate nextRetryTimestamp (exponential backoff)
4. Publish to notification.send.retry with headers
5. RetryConsumer polls, checks timestamp
6. If delay elapsed → republish to notification.send
7. SenderConsumer retries sending
```

---

## ⚙️ Configuration

### Environment Variables (.env)

The application uses a `.env` file for sensitive configuration:

```env
# Required: Gmail SMTP (for email notifications)
GMAIL_USERNAME=your.email@gmail.com
GMAIL_APP_PASSWORD=xxxx-xxxx-xxxx-xxxx

# Optional: Override defaults
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
POSTGRES_URL=jdbc:postgresql://localhost:5433/notification
POSTGRES_USERNAME=notification
POSTGRES_PASSWORD=12345
```

### Application Properties

**Key Configurations** (`application.properties`):

```properties
# Server
server.port=8081

# Kafka
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.producer.acks=all
spring.kafka.producer.properties.enable.idempotence=true
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.max-poll-records=10

# Database
spring.datasource.url=jdbc:postgresql://localhost:5433/notification
spring.jpa.hibernate.ddl-auto=update

# Email
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=${GMAIL_USERNAME:}
spring.mail.password=${GMAIL_APP_PASSWORD:}
```

### Docker Compose Services

```yaml
services:
  kafka:
    image: apache/kafka:3.8.1
    ports:
      - "9092:9092"  # External (host connections)
    environment:
      KAFKA_LISTENERS: INTERNAL://0.0.0.0:19092,EXTERNAL://0.0.0.0:9092
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:19092,EXTERNAL://localhost:9092

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports:
      - "9081:8080"
    environment:
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:19092

  redis:
    image: redis:latest
    ports:
      - "6379"
```

---

## 📡 API Usage

### Create Notification (Async)

**Endpoint:** `POST /api/v1/notifications`

**Request:**
```bash
curl -X POST http://localhost:8081/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "organizationId": "org-123",
    "title": "Password Reset",
    "message": "Click the link to reset your password",
    "recipient": "john.doe",
    "recipientEmail": "john@example.com",
    "type": "EMAIL"
  }'
```

**Response (202 Accepted):**
```json
{
  "success": true,
  "message": "Notification accepted for processing",
  "data": {
    "id": 352,
    "uuid": "034f2285-9214-4fad-b038-1c4076e2b3a3",
    "organizationId": "org-123",
    "title": "Password Reset",
    "message": "Click the link to reset your password",
    "status": "PENDING",
    "retryCount": 0,
    "maxRetries": 5,
    "createdAt": "2026-01-19T17:00:20.812943751"
  }
}
```

### Get Notification Status

**Endpoint:** `GET /api/v1/notifications/{id}`

```bash
curl http://localhost:8081/api/v1/notifications/352
```

**Response:**
```json
{
  "success": true,
  "data": {
    "id": 352,
    "uuid": "034f2285-9214-4fad-b038-1c4076e2b3a3",
    "status": "SENT",
    "sentAt": "2026-01-19T17:00:22.735972",
    "providerId": "352",
    "providerName": "EMAIL"
  }
}
```

### Query Notifications

**By Recipient:**
```bash
GET /api/v1/notifications/recipient/{recipient}
```

**By Status:**
```bash
GET /api/v1/notifications/status/SENT
```

**By Type:**
```bash
GET /api/v1/notifications/type/EMAIL
```

**Paginated:**
```bash
GET /api/v1/notifications/paginated?page=0&size=10&sort=createdAt,desc
```

---

## 📊 Monitoring

### Kafka UI Dashboard

Access at: **http://localhost:9081**

Features:
- ✅ View all topics and partitions
- ✅ Monitor consumer group lag
- ✅ Inspect messages (including DLQ)
- ✅ Real-time message rates
- ✅ Partition distribution

**Key Metrics to Monitor:**
1. **Consumer Lag** - Should be near 0 under normal load
2. **DLQ Message Count** - Permanent failures requiring investigation
3. **Message Rate** - Throughput per topic
4. **Partition Balance** - Even distribution across partitions

### Application Logs

```bash
# View Kafka consumer logs
tail -f logs/spring.log | grep "SenderConsumer\|RetryConsumer"

# View notification processing
tail -f logs/spring.log | grep "Notification.*successfully\|published to Kafka"
```

### Health Check

```bash
# Application health
curl http://localhost:8081/actuator/health

# Database connectivity
curl http://localhost:8081/actuator/health/db
```

---

## 🗄️ Database Schema

### notifications Table

```sql
CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID UNIQUE NOT NULL,
    organization_id VARCHAR(255),

    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    recipient_email VARCHAR(255),

    type VARCHAR(50) NOT NULL,  -- EMAIL, SMS, PUSH, IN_APP
    status VARCHAR(50) NOT NULL, -- PENDING, PROCESSING, SENT, DELIVERED, READ, RETRYING, FAILED

    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 5,

    error_code VARCHAR(100),
    error_message TEXT,

    provider_id VARCHAR(255),
    provider_name VARCHAR(50),

    created_at TIMESTAMP NOT NULL,
    sent_at TIMESTAMP,
    read_at TIMESTAMP
);

CREATE INDEX idx_notifications_uuid ON notifications (uuid);
CREATE INDEX idx_notifications_org_status ON notifications (organization_id, status);
CREATE INDEX idx_notifications_org_created ON notifications (organization_id, created_at DESC);
```

### Notification States

```
PENDING     → Initial state after API request
PROCESSING  → Being sent by SenderConsumer
SENT        → Successfully sent via provider
DELIVERED   → Delivered to recipient (future)
READ        → Read by recipient (future)
RETRYING    → Waiting for retry after failure
FAILED      → Permanently failed (max retries exceeded)
```

---

## 🐛 Troubleshooting

### Kafka Not Connecting

**Issue:** Application can't connect to Kafka
```
Connection to node -1 could not be established
```

**Solution:**
```bash
# Check Kafka is running
docker ps | grep kafka

# Restart Kafka
docker compose restart kafka

# Check Kafka logs
docker logs notification-kafka --tail 50
```

### Kafka UI Shows Loading Forever

**Issue:** Kafka UI can't connect to broker

**Solution:**
```bash
# Stop and remove containers
docker rm -f notification-kafka-ui notification-kafka

# Start with corrected config
docker compose up -d kafka kafka-ui

# Access at http://localhost:9081
```

### Email Not Sending

**Issue:** Emails stay in PENDING status

**Checklist:**
1. ✅ `.env` file exists with `GMAIL_USERNAME` and `GMAIL_APP_PASSWORD`
2. ✅ Gmail App Password is correct (16 characters, no spaces)
3. ✅ Kafka consumers are running (check logs)
4. ✅ Check application logs for SMTP errors

### Database Schema Mismatch

**Issue:** `column uuid does not exist`

**Solution:**
```bash
# Drop and recreate table (development only!)
PGPASSWORD=12345 psql -h localhost -p 5433 -U notification -d notification \
  -c "DROP TABLE IF EXISTS notifications CASCADE;"

# Restart application to recreate schema
./gradlew bootRun
```

---

## 🧪 Testing

### Load Testing

Send multiple notifications concurrently:

```bash
for i in {1..100}; do
  curl -X POST http://localhost:8081/api/v1/notifications \
    -H "Content-Type: application/json" \
    -d "{
      \"organizationId\": \"org-$((i % 10))\",
      \"title\": \"Test $i\",
      \"message\": \"Load test message $i\",
      \"recipient\": \"user$i\",
      \"recipientEmail\": \"test$i@example.com\",
      \"type\": \"EMAIL\"
    }" &
done
wait

echo "Sent 100 notifications"
```

### Monitor Processing

```bash
# Watch Kafka UI for real-time metrics
open http://localhost:9081

# Watch consumer lag
watch -n 1 'curl -s http://localhost:9081/api/clusters/notification-cluster/consumer-groups'

# Check database status distribution
PGPASSWORD=12345 psql -h localhost -p 5433 -U notification -d notification \
  -c "SELECT status, COUNT(*) FROM notifications GROUP BY status;"
```

---

## 📚 Project Structure

```
notification/
├── src/main/java/com/notification/notification/
│   ├── config/              # Kafka, Email, CORS configuration
│   │   ├── KafkaConfig.java
│   │   ├── KafkaTopicConfig.java
│   │   └── EmailConfig.java
│   ├── controller/          # REST API endpoints
│   │   └── NotificationController.java
│   ├── dto/                 # Request/Response DTOs
│   │   ├── NotificationRequest.java
│   │   └── NotificationResponse.java
│   ├── entity/              # JPA entities
│   │   ├── Notification.java
│   │   ├── NotificationStatus.java
│   │   └── NotificationType.java
│   ├── messaging/           # Kafka producers/consumers
│   │   ├── consumer/
│   │   │   ├── SenderConsumer.java
│   │   │   └── RetryConsumer.java
│   │   ├── producer/
│   │   │   ├── NotificationProducer.java
│   │   │   └── EventProducer.java
│   │   └── dto/
│   │       ├── NotificationMessage.java
│   │       └── NotificationEvent.java
│   ├── repository/          # Database repositories
│   │   └── NotificationRepository.java
│   ├── service/             # Business logic
│   │   ├── NotificationService.java
│   │   └── NotificationServiceImpl.java
│   ├── sender/              # Notification providers
│   │   ├── NotificationSender.java
│   │   ├── NotificationSenderFactory.java
│   │   └── email/
│   │       ├── EmailSender.java
│   │       └── SmtpEmailSender.java
│   └── exception/           # Custom exceptions
│       ├── TransientSendException.java
│       └── PermanentSendException.java
├── compose.yaml             # Docker Compose (Kafka, Kafka UI, Redis)
├── .env                     # Environment variables (not in git)
├── build.gradle             # Dependencies
└── README.md                # This file
```

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📝 License

This project is for educational purposes.

---

# 📖 Design Document

## Idempotency Strategy

Kafka provides **at-least-once delivery**, so deduplication is required:

* `notificationUuid` (UUID) generated on creation
* Stored with UNIQUE constraint in database
* SenderConsumer checks if notification already `SENT` before processing
* If consumer crashes before ACK, message redelivered → idempotency prevents duplicates

---

## Database Design Rationale

### Enums

```sql
CREATE TYPE notification_status AS ENUM (
  'PENDING',
  'PROCESSING',
  'SENT',
  'FAILED',
  'RETRYING'
);

CREATE TYPE notification_channel AS ENUM (
  'EMAIL',
  'SMS',
  'PUSH'
);
```

---

### Primary Table: `notifications`

```sql
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT random_uuid() or inc_id(),
    organization_id UUID NOT NULL,

    event_type VARCHAR(100) NOT NULL,
    channel notification_channel NOT NULL,
    recipient VARCHAR(255) NOT NULL,

    payload JSONB NOT NULL,
    status notification_status NOT NULL,

    provider VARCHAR(50),
    provider_message_id VARCHAR(255),

    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 5,

    error_code VARCHAR(100),
    error_message TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMP
);
```

---

### Indexing Strategy

```sql
-- Org-based queries
CREATE INDEX idx_notifications_org_created
ON notifications (organization_id, created_at DESC);

-- Status queries
CREATE INDEX idx_notifications_org_status
ON notifications (organization_id, status);

-- Retry worker optimization
CREATE INDEX idx_notifications_retry
ON notifications (status, retry_count)
WHERE status IN ('FAILED', 'RETRYING');
```

---

## 7. Audit Table: `notification_events`

Stores full lifecycle history of each notification.

```sql
CREATE TABLE notification_events (
    id BIGSERIAL PRIMARY KEY,
    notification_id UUID NOT NULL,
    organization_id UUID NOT NULL,

    event VARCHAR(50) NOT NULL, -- CREATED, SEND_ATTEMPT, SENT, FAILED, RETRIED
    details JSONB,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_notification
        FOREIGN KEY (notification_id)
        REFERENCES notifications(id)
        ON DELETE CASCADE
);
```

### Indexes

```sql
CREATE INDEX idx_events_notification
ON notification_events (notification_id);

CREATE INDEX idx_events_org_created
ON notification_events (organization_id, created_at DESC);
```

---

## 8. Notification State Machine

```
PENDING
   ↓
PROCESSING
   ↓
SENT
   ↓
FAILED → RETRYING → SENT
            ↓
           DLQ (Dead Letter Queue)
```

---

## 9. Retry & Failure Handling (Apache Kafka)

### Retry Flow

```
Message Processing Failed
        |
        v
Check retry_count < max_retries?
        |
   YES  |  NO
        |   \
        v    v
Publish to      Publish to DLQ
notification.send.retry   (permanent failure)
(with backoff headers)    (notification.send.dlq)
        |
        v
RetryConsumer
polls retry topic
        |
        v
Check X-Next-Retry-Timestamp
        |
        v
Delay elapsed?
   YES  |  NO
        |   \
        v    v
Republish   Pause &
to send    poll again
topic      later
```

### Implementation Details

* **Transient failures** (network, rate limit): Publish to `notification.send.retry` with exponential backoff timestamp
* **Permanent failures** (invalid recipient, auth error): Publish directly to `notification.send.dlq`
* **Retry with backoff**: Single retry topic with timestamp-based delays
  * Retry 1: 5 seconds
  * Retry 2: 30 seconds
  * Retry 3: 2 minutes
  * Retry 4: 10 minutes
  * Retry 5: 30 minutes
* **Max retries exceeded**: Publish to `notification.send.dlq`
* All failures recorded in `notification.events` topic (event sourcing)

### Kafka Consumer Acknowledgment

```java
// On success
acknowledgment.acknowledge(); // Manual commit

// On transient failure (retry)
// Publish to retry topic with nextRetryTimestamp header
notificationProducer.publishToRetry(message, calculateBackoff(retryCount));
acknowledgment.acknowledge(); // Commit after publishing to retry

// On permanent failure
// Publish to DLQ topic
notificationProducer.publishToDlq(message, errorDetails);
acknowledgment.acknowledge(); // Commit after publishing to DLQ
```

---

## 10. Multi-Tenancy & Security

* `organizationId` is mandatory in:

  * Kafka message
  * Database schema
* Organization context must come from:

  * Kafka headers
  * API gateway
  * Auth token (JWT)

// note: Planning to add security at the last

All queries MUST include:

```sql
WHERE organization_id = :orgId
```

---

## 11. Observability

* Correlation ID = `notificationId`
* Structured logging
* Metrics:

  * Send success rate
  * Retry count
  * Processing latency

---

## 12. Extensibility

Designed to easily add:

* New channels (WhatsApp, Slack)
* Multiple providers per channel
* Template management
* Rate limiting per organization
* DB partitioning by `organization_id`

---

## 13. Design Principles

## Software Design Patterns Used

This service intentionally applies the following **software design patterns** to ensure scalability, maintainability, and reliability.

---

### Event-Driven Architecture (EDA)
Used to decouple notification producers from consumers using Apache Kafka. Enables asynchronous processing, scalability, and fault isolation.

---

### Producer–Consumer Pattern
Kafka producers publish notification messages to topics, and consumer groups process them independently with partitioning, allowing horizontal scalability and parallel processing with at-least-once delivery guarantees.

---

### Strategy Pattern
Applied to notification sending logic to support multiple channels and providers (Email, SMS, Push) without conditional branching.

---

### Factory Pattern
Used to create appropriate notification sender implementations based on channel and provider at runtime.

---

### State Pattern
Models the notification lifecycle (PENDING, PROCESSING, SENT, FAILED, RETRYING) and enforces valid state transitions.

---

### Idempotent Consumer Pattern
Ensures duplicate Kafka messages do not result in duplicate notifications by using `notificationUuid` (UUID field) as a unique key with status checking before processing.

---

### Retry Pattern
Handles transient failures by retrying notification delivery with controlled retry limits and backoff.

---

### Dead Letter Queue (DLQ) Pattern
Routes permanently failed notification messages to a dedicated topic for inspection and manual intervention.

---

### Repository Pattern
Abstracts database access logic, keeping domain logic independent of persistence concerns.

---

### Single Responsibility Principle (SRP)
Each component (consumer, sender, retry handler, persistence layer) has a single, well-defined responsibility.


---

## 16. Conclusion

This design provides a **scalable, auditable, and production-ready** notification platform suitable for multi-tenant systems.

**Why Apache Kafka?**
* **Event Sourcing**: Full audit trail via immutable event log (`notification.events` topic)
* **Event Replay**: Reprocess notifications from any point in time (up to 30-day retention)
* **Horizontal Scalability**: Partition-based parallelism enables 10K-100K msgs/sec throughput
* **Multi-Tenancy**: Partition by `organizationId` for ordering and isolation
* **Real-time Analytics**: Kafka Streams for delivery metrics and monitoring
* **Long-term Retention**: 30-day retention for audit and compliance
* **At-Least-Once Delivery**: Manual acknowledgment with idempotency guarantees
* **Production-Ready**: Mature ecosystem (Kafka UI, monitoring, observability)

This document serves as the **single source of truth** for:

* Architecture decisions
* Data modeling
* Apache Kafka messaging strategy
* Multi-tenancy design
* Future evolution

---