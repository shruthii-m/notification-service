# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Always ask clarifying questions when there are multiple valid approaches to a task.

## Project Overview

This is a **multi-tenant, event-driven notification microservice** built with Spring Boot 3.3.5 and Java 21. The service is designed to process and deliver notifications (Email, SMS, WhatsApp, Push, In-App) for multiple organizations using Apache Kafka as the message broker.

## Core Architecture

### Technology Stack
- **Framework**: Spring Boot 3.3.5
- **Java Version**: 21
- **Build Tool**: Gradle
- **Database**: PostgreSQL (port 5433)
- **Message Broker**: Apache Kafka 3.8.1 (KRaft mode, no Zookeeper)
- **Monitoring**: Kafka UI (port 9081)
- **Caching**: Redis (via Docker Compose)
- **ORM**: Spring Data JPA with Hibernate
- **Email Provider**: SMTP (Gmail integration via .env configuration)

### Current State vs Design
The README.md contains the complete architectural design document. The codebase is in **active development** with core async messaging infrastructure complete.

**✅ Implemented:**
- REST API with CRUD operations (returns 202 Accepted for async processing)
- JPA entity model with extended status/type enums (PENDING, PROCESSING, RETRYING, SENT, DELIVERED, READ, FAILED)
- Controller with HATEOAS support and pagination
- **Apache Kafka integration** (4 topics: send, retry, DLQ, events)
- **Event-driven architecture** with producers and consumers
- **Retry mechanism** with exponential backoff (5 levels: 5s, 30s, 2m, 10m, 30m)
- **Dead Letter Queue (DLQ)** for permanent failures
- **Event sourcing** via `notification.events` topic (30-day retention)
- **Idempotency** using UUID field for deduplication
- **Email provider integration** (SMTP with Gmail)
- Multi-tenancy entity support (organizationId field added, used as Kafka partition key)

**⚠️ Partially Implemented:**
- Multi-tenancy: organizationId field exists but **not enforced in queries** (security gap)

**❌ Not Yet Implemented:**
- SMS/WhatsApp/Push provider integrations (only Email works)
- Query-level multi-tenancy enforcement (organizationId filtering)
- Authentication and authorization (no security layer)
- Notification templates system
- Rate limiting per organization
- Webhook callbacks for delivery events
- Advanced analytics and reporting

## Common Development Commands

### Build & Run
```bash
# Build the project
./gradlew build

# Run the application
./gradlew bootRun

# Build without tests
./gradlew build -x test

# Clean build
./gradlew clean build
```

### Testing
```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests com.notification.notification.ControllerTests

# Run tests with detailed output
./gradlew test --info
```

### Docker
```bash
# Start all services (Kafka, Kafka UI, Redis)
docker compose up -d

# Start only Kafka and Kafka UI
docker compose up -d kafka kafka-ui

# View Kafka logs
docker logs -f notification-kafka

# Stop all services
docker compose down

# Access Kafka UI
# Open browser: http://localhost:9081
```

### Database Setup
PostgreSQL connection details (from application.properties):
- **URL**: jdbc:postgresql://localhost:5433/notification
- **Username**: notification
- **Password**: 12345
- **Port**: 8081 (application server)

## Code Structure

### Package Organization
```
com.notification.notification/
├── config/          - Application configuration (CORS, Kafka config, topic definitions)
├── controller/      - REST API endpoints
├── dto/            - Request/response DTOs
├── entity/         - JPA entities and enums
├── exception/      - Custom exceptions and global handler
├── messaging/       - Kafka integration (NEW)
│   ├── consumer/    - Kafka consumers (SenderConsumer, RetryConsumer)
│   ├── dto/         - Kafka message DTOs (NotificationMessage, NotificationEvent)
│   └── producer/    - Kafka producers (NotificationProducer, EventProducer)
├── repository/     - Spring Data JPA repositories
├── sender/         - Provider integrations (EmailSender, SendResult, etc.)
├── service/        - Business logic (interface + implementation)
└── util/           - Utility classes
```

### Key Components

**Entity Model** (`entity/Notification.java`):
- Uses Lombok annotations (@Data, @Builder, etc.)
- Automatic UUID generation and timestamp management via @PrePersist
- **UUID field** for Kafka idempotency (unique, non-null)
- **organizationId field** for multi-tenancy (used as Kafka partition key)
- **Retry tracking**: retryCount, maxRetries (default 5)
- **Error tracking**: errorCode, errorMessage
- **Provider info**: providerId, providerName
- Enums: NotificationStatus (PENDING, **PROCESSING**, SENT, DELIVERED, READ, **RETRYING**, FAILED)
- Enums: NotificationType (EMAIL, SMS, PUSH, IN_APP, WHATSAPP)

**Controller Pattern** (`controller/NotificationController.java`):
- RESTful endpoints at `/api/v1/notifications`
- **Returns 202 Accepted** for async notification processing (not 201 Created)
- Uses HATEOAS for hypermedia links
- Supports both paginated and non-paginated queries
- Comprehensive filtering by recipient, status, type

**Service Layer** (`service/NotificationServiceImpl.java`):
- Interface-based design (NotificationService + NotificationServiceImpl)
- **Async processing**: Publishes to Kafka instead of direct sending
- Creates notification with PENDING status, returns immediately
- Kafka consumers handle actual sending in background

**Kafka Producers** (`messaging/producer/`):
- `NotificationProducer`: Publishes to send/retry/DLQ topics
- `EventProducer`: Publishes audit events to notification.events topic
- Uses organizationId as partition key for ordering and isolation
- Adds correlation headers for distributed tracing

**Kafka Consumers** (`messaging/consumer/`):
- `SenderConsumer`: Main consumer for sending notifications (3-way concurrency)
  - Fetches from DB, checks idempotency (skips if already SENT)
  - Updates status to PROCESSING, sends via provider
  - On success: marks SENT, publishes event
  - On transient failure: schedules retry with exponential backoff
  - On permanent failure: marks FAILED, sends to DLQ
  - **Uses manual acknowledgment** (do NOT add @Transactional - causes conflicts)
- `RetryConsumer`: Processes delayed retries from retry topic
  - Checks nextRetryTimestamp, pauses if not ready
  - Republishes to send topic when delay elapsed

## Architecture Design Principles

When implementing features, follow these design patterns:

### Event-Driven Architecture (Kafka)
- Producers publish to Kafka topics
- Consumers process messages from topics with manual acknowledgment
- **4 Topics**:
  - `notification.send` (6 partitions) - Ready to send notifications
  - `notification.send.retry` (3 partitions) - Failed notifications awaiting retry
  - `notification.send.dlq` (1 partition) - Permanent failures
  - `notification.events` (6 partitions, 30-day retention) - Audit trail
- Use organizationId as partition key for ordering and multi-tenant isolation

### Multi-Tenancy
- **Critical**: All queries MUST filter by `organization_id` (⚠️ **NOT YET ENFORCED**)
- organizationId used as Kafka partition key (ensures per-org ordering)
- Implement row-level isolation per organization in repositories
- **Security Gap**: Currently organizationId is not enforced in query methods

### Message Flow (Implemented)
```
REST API (202 Accepted)
  ↓
Service saves to DB (PENDING) + publishes to Kafka
  ↓
notification.send topic (partitioned by organizationId)
  ↓
SenderConsumer (3 concurrent workers)
  - Fetch from DB by UUID
  - Idempotency check (skip if SENT)
  - Update to PROCESSING
  - Send via provider (EmailSender)
  ↓
SUCCESS: Update to SENT → Publish to notification.events
TRANSIENT_FAILURE: Retry count < 5 → Publish to notification.send.retry (with delay)
PERMANENT_FAILURE: Mark FAILED → Publish to notification.send.dlq
  ↓
RetryConsumer processes retry topic
  - Check nextRetryTimestamp
  - Pause if delay not elapsed
  - Republish to notification.send when ready
```

### Retry Strategy (Exponential Backoff)
- Uses Kafka retry topic (not DLX, since Kafka doesn't have DLX like RabbitMQ)
- **5 retry levels** with exponential backoff:
  - Retry 1: 5 seconds
  - Retry 2: 30 seconds
  - Retry 3: 2 minutes
  - Retry 4: 10 minutes
  - Retry 5: 30 minutes
- Max retries configurable via `maxRetries` field (default 5)
- After max retries: route to DLQ topic
- Permanent failures (invalid recipient, auth errors) go directly to DLQ

### Idempotency
- Notification entity has **UUID field** (unique, auto-generated)
- Kafka messages use UUID as identifier (not DB ID)
- Consumer checks status before sending (skip if already SENT)
- Critical for Kafka's at-least-once delivery semantics
- Database enforces UUID uniqueness constraint

## Development Guidelines

### Adding New Features
1. Check README.md design document for architectural alignment
2. Multi-tenancy isolation is mandatory (add organization_id to all tables/queries)
3. Follow existing patterns: Controller → Service Interface → Service Impl → Repository
4. Use Lombok annotations to reduce boilerplate
5. Add validation using Jakarta validation annotations
6. Maintain HATEOAS links in responses
7. **For async operations**: Publish to Kafka instead of direct processing
8. **Always add correlation headers** to Kafka messages for distributed tracing

### Kafka Development Guidelines
- **Never add @Transactional to Kafka consumers** (conflicts with manual acknowledgment)
- Always use manual acknowledgment (`enable.auto.commit=false`)
- Commit offset only after successful DB update (at-least-once guarantee)
- Use organizationId as partition key for all notification messages
- Add proper exception handling: TransientSendException vs PermanentSendException
- Test with Kafka UI (http://localhost:9081) to verify message flow
- Check consumer lag in Kafka UI (should be near 0 under normal load)

### Database Changes
- Schema managed by Hibernate (spring.jpa.hibernate.ddl-auto=update)
- For production, use proper migrations (Flyway/Liquibase)
- Always add proper indexes for organization_id queries
- **UUID field is required** for all entities that use Kafka messaging

### Testing
- Tests should go in src/test/java with matching package structure
- Use @SpringBootTest for integration tests
- Use @DataJpaTest for repository tests
- Use @WebMvcTest for controller tests
- **Kafka integration tests**: Use Testcontainers with KafkaContainer
- Use Awaitility library for async verification (`await().atMost(10, SECONDS).until(...)`)

### Logging
- All components use @Slf4j (Lombok)
- Log at INFO level for request/response and Kafka message processing
- Include UUID and organizationId in log messages for correlation
- **Kafka consumers**: Log topic, partition, offset, and message key

## Important Notes

- **Kafka is Production-Ready**: Core async architecture is implemented and tested
- **No Auth/Security**: Currently no authentication or authorization (planned for later)
- **Multi-Tenancy Security Gap**: organizationId exists but not enforced in queries
- **Event Sourcing Active**: All state changes logged to `notification.events` topic (30-day retention)
- **Hibernate DDL**: Using auto-schema generation (switch to Flyway/Liquibase for production)
- **.env Required**: Must configure Gmail SMTP credentials in .env file (see README.md)
- **Kafka UI Available**: Monitor topics and consumers at http://localhost:9081

## Common Issues & Troubleshooting

### Transaction Rollback Errors
**Error**: `UnexpectedRollbackException: Transaction silently rolled back`
**Cause**: `@Transactional` on Kafka consumer with manual acknowledgment
**Fix**: Remove `@Transactional` from consumer methods

### Kafka UI Loading Forever
**Error**: Kafka UI shows loading spinner, no brokers visible
**Cause**: Kafka UI trying to connect to wrong listener (localhost instead of kafka hostname)
**Fix**: Ensure `KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=kafka:19092` in compose.yaml

### Database Schema Mismatch
**Error**: `column uuid does not exist` or similar
**Cause**: Database table created before entity changes
**Fix**: Drop table and restart application (Hibernate will recreate)
```bash
PGPASSWORD=12345 psql -h localhost -p 5433 -U notification -d notification \
  -c "DROP TABLE IF EXISTS notifications CASCADE;"
```

### Email Not Sending
**Error**: Email notification stuck in PROCESSING
**Cause**: Missing or incorrect .env configuration
**Fix**: Check .env file has valid Gmail credentials (app password, not regular password)

## Future Implementation Priorities

1. ✅ ~~Kafka setup (topics, producers, consumers)~~ **DONE**
2. ⚠️ **Enforce multi-tenancy in queries** (organization_id filtering) - **HIGH PRIORITY**
3. Add provider integrations (SMS via Twilio, WhatsApp, Push via FCM)
4. Add security and JWT-based authentication
5. Implement notification templates system
6. Add rate limiting per organization (prevent abuse)
7. Implement webhook callbacks for delivery events
8. Add observability (Prometheus metrics, distributed tracing)
9. Switch from Hibernate DDL to Flyway migrations
10. Implement exactly-once semantics with Kafka transactions (currently at-least-once)
