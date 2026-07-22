# Patient Management

A production-grade **Spring Boot microservices** system for managing patients, billing, and
analytics. Each service is an **independent, standalone Spring Boot application** — its own
build, its own database, its own deployable — following the true microservices model rather
than a shared multi-module build.

> **Contributing / conventions:** architecture rules, layering, and coding standards live in
> [`CLAUDE.md`](CLAUDE.md). Read it before adding a service or opening a PR. Planned hardening
> work (resilience, observability, security) is tracked in [`ROADMAP.md`](ROADMAP.md).

## Architecture

```
                         ┌─────────────┐
        client  ───────▶ │ api-gateway │  (single entry point)
                         └──────┬──────┘
             ┌──────────────────┼──────────────────┐
             ▼                  ▼                  ▼
      ┌──────────────┐  ┌──────────────┐   ┌──────────────┐
      │ auth-service │  │patient-service│──▶│billing-service│  (gRPC)
      └──────────────┘  └──────┬───────┘   └──────────────┘
                               │ event
                               ▼
                       ┌──────────────────┐
                       │ analytics-service│  (Kafka consumer)
                       └──────────────────┘

  each service ──▶ its own MariaDB database
```

## Tech stack

| Concern      | Choice                                                        |
| ------------ | ------------------------------------------------------------- |
| Language     | Java 17                                                       |
| Framework    | Spring Boot 3.5.x                                             |
| Build        | Maven (per-service `pom.xml`)                                 |
| Database     | MariaDB (InnoDB), one database per service                    |
| Migrations   | Flyway (versioned SQL)                                        |
| Messaging    | Kafka (event-driven flows) *(planned)*                        |
| Sync RPC     | gRPC (patient → billing) *(planned)*                          |
| Gateway      | Spring Cloud Gateway *(planned)*                              |
| Containers   | Docker + docker-compose                                       |

## Services

| Service             | Responsibility                                   | Status         |
| ------------------- | ------------------------------------------------ | -------------- |
| `patient-service`   | Core patient CRUD (REST + JPA + MariaDB)         | 🚧 In progress |
| `billing-service`   | Billing accounts, called via gRPC                | 📋 Planned     |
| `analytics-service` | Consumes domain events from Kafka                | 📋 Planned     |
| `auth-service`      | Authentication, JWT issuing & validation         | 📋 Planned     |
| `api-gateway`       | Single entry point, routing to services          | 📋 Planned     |

## Prerequisites

- **JDK 17+**
- **Maven** (or use the bundled `./mvnw` wrapper — no install needed)
- **Docker** & **Docker Compose** (for databases and infrastructure)

## Getting started

```bash
# clone
git clone <repo-url>
cd patient-management

# start infrastructure (databases, etc.) — coming soon
docker compose up -d

# build & run a single service
cd patient-service
./mvnw spring-boot:run
```

Each service exposes standard Spring Boot Actuator endpoints, e.g. health at:

```
GET http://localhost:8080/actuator/health
```

## Project layout

```
patient-management/
├── patient-service/     # core patient service (reference implementation)
├── billing-service/     # planned
├── analytics-service/   # planned
├── auth-service/        # planned
├── api-gateway/         # planned
├── infrastructure/      # shared docker-compose (databases, Kafka)
├── CLAUDE.md            # architecture & coding conventions
└── README.md
```

## Development conventions

Layered architecture per service (`model → repository → service → dto → mapper →
controller → exception`), entity/DTO separation, Flyway-managed schema, and `@Transactional`
service methods on an InnoDB (ACID) database. Full details in [`CLAUDE.md`](CLAUDE.md).

## License

TBD
