# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Always ask clarifying questions when there are multiple valid approaches to a task.

## Project Overview

This is a **multi-tenant, event-driven notification microservice** built with Spring Boot 3.3.5 and Java 21. The service is designed to process and deliver notifications (Email, SMS, Whats app, Push, In-App) for multiple organizations using RabbitMQ as the message broker.

## Core Architecture

### Technology Stack
- **Framework**: Spring Boot 3.3.5
- **Java Version**: 21
- **Build Tool**: Gradle
- **Database**: PostgreSQL (port 5433)
- **Message Broker**: RabbitMQ (planned, not yet implemented)
- **Caching**: Redis (via Docker Compose)
- **ORM**: Spring Data JPA with Hibernate

### Current State vs Design
The README.md contains the complete architectural design document, but the codebase is currently in **early implementation phase**. Key items implemented:
- Basic REST API with CRUD operations
- JPA entity model with status/type enums
- Controller with HATEOAS support
- Pagination support
- Basic exception handling

**Not yet implemented** (per design doc):
- RabbitMQ integration (message queues, exchanges, consumers)
- Multi-tenancy (organization_id isolation)
- Event sourcing and audit tables
- Retry/DLQ mechanisms
- Provider integrations (Email/SMS/Whats app/Push services)
- Idempotency handling

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
# Start Redis via Docker Compose
docker compose up -d

# Stop services
docker compose down
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
├── config/          - Application configuration (CORS, beans)
├── controller/      - REST API endpoints
├── dto/            - Request/response DTOs
├── entity/         - JPA entities and enums
├── exception/      - Custom exceptions and global handler
├── repository/     - Spring Data JPA repositories
├── service/        - Business logic (interface + implementation)
└── util/           - Utility classes
```

### Key Components

**Entity Model** (`entity/Notification.java`):
- Uses Lombok annotations (@Data, @Builder, etc.)
- Automatic timestamp management via @PrePersist
- Enums: NotificationStatus (PENDING, SENT, DELIVERED, READ, FAILED)
- Enums: NotificationType (EMAIL, SMS, PUSH, IN_APP)

**Controller Pattern** (`controller/NotificationController.java`):
- RESTful endpoints at `/api/v1/notifications`
- Uses HATEOAS for hypermedia links
- Supports both paginated and non-paginated queries
- Comprehensive filtering by recipient, status, type

**Service Layer**:
- Interface-based design (NotificationService + NotificationServiceImpl)
- Transaction management handled by Spring

## Architecture Design Principles (from README)

When implementing features, follow these design patterns:

### Event-Driven Architecture
- Producers publish to RabbitMQ exchanges
- Consumers process messages from queues
- Use routing keys: `notification.<action>.<channel>`

### Multi-Tenancy
- **Critical**: All queries MUST filter by `organization_id`
- Implement row-level isolation per organization
- Currently not enforced - needs implementation

### Message Flow (Planned)
```
REST API → RabbitMQ (notification.exchange)
         → notification.requested queue
         → Notification Service (validate, persist, deduplicate)
         → notification.send queue
         → Sender Worker (deliver via provider)
         → Update status + audit events
```

### Retry Strategy
- Use RabbitMQ Dead Letter Exchange (DLX) for retries
- Exponential backoff with TTL queues
- Max 5 retries (configurable)
- Permanent failures route to DLQ

### Idempotency
- Use notification UUID as primary key
- Database-level deduplication (INSERT ON CONFLICT DO NOTHING)
- Critical for at-least-once delivery guarantees

## Development Guidelines

### Adding New Features
1. Check README.md design document for architectural alignment
2. Multi-tenancy isolation is mandatory (add organization_id to all tables/queries)
3. Follow existing patterns: Controller → Service Interface → Service Impl → Repository
4. Use Lombok annotations to reduce boilerplate
5. Add validation using Jakarta validation annotations
6. Maintain HATEOAS links in responses

### Database Changes
- Schema managed by Hibernate (spring.jpa.hibernate.ddl-auto=update)
- For production, use proper migrations (Flyway/Liquibase)
- Always add proper indexes for organization_id queries

### Testing
- Tests should go in src/test/java with matching package structure
- Use @SpringBootTest for integration tests
- Use @DataJpaTest for repository tests
- Use @WebMvcTest for controller tests

### Logging
- Controller uses @Slf4j (Lombok)
- Log at INFO level for request/response
- Use correlation IDs for tracing (to be implemented)

## Important Notes

- **Work in Progress**: Many components from the design doc are not yet implemented
- **No Auth/Security**: Currently no authentication or authorization (planned for later)
- **No RabbitMQ Integration**: The core event-driven architecture is not yet implemented
- **No Audit Trail**: The notification_events table for audit history is not yet created
- **Hibernate DDL**: Using auto-schema generation (not production-ready)

## Future Implementation Priorities (per README)

1. RabbitMQ setup (exchanges, queues, bindings, DLX/DLQ)
2. Add organization_id to entities and enforce multi-tenancy
3. Implement message producers/consumers
4. Add provider integrations (SendGrid, Twilio, FCM, etc.)
5. Implement retry logic with exponential backoff
6. Add audit events table and tracking
7. Implement idempotency with UUID primary keys
8. Add security and JWT-based authentication
9. Implement observability (metrics, tracing, structured logging)
