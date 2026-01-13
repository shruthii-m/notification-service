# The goal of my thinking as a completion creteria for the project is outlined below.
* This is how the outcome of this project should be looking like.
* I will be updating this project incrementally.


# Notification Service – Design Document

## 1. Overview

The **Notification Service** is a multi-tenant, event-driven microservice responsible for processing and delivering notifications (Email, SMS, Push) for multiple organizations.

It is designed to be:

* **Scalable**
* **Fault-tolerant**
* **Auditable**
* **RabbitMQ-driven** (message broker for reliable task processing)
* **Multi-tenant (organization-based isolation)**

---

## 2. High-Level Goals

* Allow **any organization** to publish notification requests via RabbitMQ
* Process notifications asynchronously
* Guarantee **at-least-once delivery** with manual acknowledgments
* Prevent duplicate notifications (idempotency)
* Store full **audit history**
* Allow organizations to **query their notification data**
* Support retries with exponential backoff and dead-letter handling

---

## 3. Architecture Overview

### Event Flow

```
User / Upstream Service
        |
        | (notification.requested)
        v
RabbitMQ Exchange (notification.exchange)
        |
        v
Queue: notification.requested
        |
        v
Notification Service (Consumer)
  - Persist notification (PENDING)
  - Validate & deduplicate
  - Publish to send queue
        |
        v
Queue: notification.send
        |
        v
Sender Worker (Consumer)
  - Send via provider (Email/SMS/Push)
  - On success: ACK message, update status (SENT)
  - On transient failure: NACK with requeue or route to retry queue
  - On permanent failure: Route to DLQ
  - Publish status event

```

---

## 4. RabbitMQ Design

### Why RabbitMQ over Kafka?

| Feature | Benefit for Notifications |
|---------|---------------------------|
| **Task Queue Semantics** | Notifications are tasks to process, not events to replay |
| **Built-in DLX (Dead Letter Exchange)** | Native retry and DLQ without custom code |
| **Per-message Acknowledgment** | Fine-grained control over message processing |
| **Message TTL** | Auto-expire stale notifications |
| **Priority Queues** | Prioritize urgent notifications |
| **Simpler Operations** | Lower complexity than Kafka clusters |

---

### Exchanges

| Exchange Name | Type | Purpose |
|---------------|------|---------|
| `notification.exchange` | Topic | Main routing exchange |
| `notification.dlx` | Direct | Dead letter exchange for failed messages |
| `notification.retry.exchange` | Direct | Delayed retry exchange |

---

### Queues

| Queue Name | Bound To | Routing Key | Purpose |
|------------|----------|-------------|---------|
| `notification.requested` | `notification.exchange` | `notification.requested` | Incoming notification requests |
| `notification.send` | `notification.exchange` | `notification.send` | Tasks for sending notifications |
| `notification.send.retry` | `notification.retry.exchange` | `notification.send.retry` | Retry queue with TTL |
| `notification.send.dlq` | `notification.dlx` | `notification.send.dlq` | Permanent failures (Dead Letter Queue) |
| `notification.status` | `notification.exchange` | `notification.status` | Status update events |

---

### Retry Strategy with Dead Letter Exchange

```
notification.send (Queue)
        |
        | (message fails, NACK without requeue)
        v
notification.dlx (Dead Letter Exchange)
        |
        v
notification.send.retry (Queue with x-message-ttl)
        |
        | (TTL expires, message re-routed)
        v
notification.exchange → notification.send
        |
        | (if max retries exceeded)
        v
notification.send.dlq (Dead Letter Queue)
```

**Queue Configuration for Retry:**
```java
// notification.send.retry queue arguments
x-message-ttl: 30000  // 30 seconds delay before retry
x-dead-letter-exchange: notification.exchange
x-dead-letter-routing-key: notification.send
```

---

### Routing Strategy

* **Routing Key Pattern**: `notification.<action>.<channel>` (optional granularity)
* Examples:
  * `notification.requested` - New notification request
  * `notification.send` - Ready to send
  * `notification.send.email` - Email-specific queue (optional)
  * `notification.status.sent` - Status update

---

### Message Contract (Example)

```json
{
  "notificationId": "uuid",
  "organizationId": "uuid",
  "eventType": "USER_REGISTERED",
  "channel": "EMAIL",
  "recipient": "user@example.com",
  "payload": {
    "name": "John",
    "link": "https://example.com"
  },
  "priority": 5,
  "retryCount": 0,
  "maxRetries": 5,
  "createdAt": "2025-01-01T10:00:00Z"
}
```

### Message Headers

| Header | Purpose |
|--------|---------|
| `x-organization-id` | Multi-tenant isolation |
| `x-correlation-id` | Tracing (same as notificationId) |
| `x-retry-count` | Track retry attempts |
| `x-original-queue` | For DLQ debugging |

---

## 5. Idempotency Strategy

RabbitMQ provides **at-least-once delivery** (with manual acknowledgment), so deduplication is required.

* `notificationId` is generated by the producer (UUID)
* Stored as **PRIMARY KEY** in DB
* Duplicate messages are safely ignored at DB level (INSERT ON CONFLICT DO NOTHING)
* Consumer uses manual ACK after successful processing
* If consumer crashes before ACK, message is redelivered → idempotency prevents duplicates

---

## 6. Database Design (PostgreSQL)

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
           DLT
```

---

## 9. Retry & Failure Handling (RabbitMQ Native)

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
   NACK     Route to DLQ
(requeue=false)  (permanent failure)
        |
        v
Dead Letter Exchange (DLX)
        |
        v
Retry Queue (with TTL)
        |
        | (after TTL expires)
        v
Re-route to original queue
```

### Implementation Details

* **Transient failures** (network, rate limit): NACK → retry queue with exponential backoff
* **Permanent failures** (invalid recipient, auth error): Route directly to DLQ
* **Retry with backoff**: Use multiple retry queues with increasing TTLs
  * `notification.retry.30s` (TTL: 30 seconds)
  * `notification.retry.5m` (TTL: 5 minutes)  
  * `notification.retry.30m` (TTL: 30 minutes)
* **Max retries exceeded**: Route to `notification.send.dlq`
* All failures recorded in `notification_events` audit table

### RabbitMQ Consumer Acknowledgment

```java
// On success
channel.basicAck(deliveryTag, false);

// On transient failure (retry)
channel.basicNack(deliveryTag, false, false); // requeue=false, goes to DLX

// On permanent failure
// Add header marking as permanent, route to DLQ
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
Used to decouple notification producers from consumers using RabbitMQ. Enables asynchronous processing, scalability, and fault isolation.

---

### Producer–Consumer Pattern
RabbitMQ producers publish notification messages to exchanges, and consumers process them independently from queues, allowing parallelism and back-pressure handling via prefetch limits.

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
Ensures duplicate Kafka messages do not result in duplicate notifications by using `notificationId` as a unique key.

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

**Why RabbitMQ?**
* Native DLX/DLQ for retry handling
* Per-message acknowledgments for reliability
* Priority queues for urgent notifications
* Simpler operational model than Kafka
* Perfect fit for task queue workloads

This document serves as the **single source of truth** for:

* Architecture decisions
* Data modeling
* RabbitMQ messaging strategy
* Future evolution

---