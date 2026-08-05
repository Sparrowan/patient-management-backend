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
        client  ───────▶ │ api-gateway │  (single entry point)          planned
                         └──────┬──────┘
             ┌──────────────────┼──────────────────┐
             ▼                  ▼                  ▼
      ┌──────────────┐  ┌───────────────┐   ┌───────────────┐
      │ auth-service │  │patient-service│──▶│billing-service│
      │   planned    │  │  REST :4000   │   │  REST :4001   │
      └──────────────┘  └──────┬────────┘   │  gRPC :9001   │
                               │            └───────────────┘
                               │       gRPC over mTLS (OpenAccount):
                               │       registering a patient opens a billing account
                               │ event
                               ▼
                       ┌──────────────────┐
                       │ analytics-service│  (Kafka consumer)            planned
                       └──────────────────┘

  each service ──▶ its own MariaDB database
```

## Tech stack

| Concern      | Choice                                                        |
| ------------ | ------------------------------------------------------------- |
| Language     | Java 17                                                       |
| Framework    | Spring Boot 3.5.x                                             |
| Build        | Maven (per-service `pom.xml`)                                 |
| Database     | MariaDB (InnoDB, ACID), one database per service              |
| Migrations   | Flyway (versioned SQL, never `ddl-auto`)                      |
| Mapping      | MapStruct (compile-time) + Lombok + Java `record` DTOs        |
| API docs     | springdoc-openapi (Swagger UI)                                |
| Sync RPC     | gRPC (patient → billing), secured with **mTLS**               |
| Messaging    | Kafka (event-driven flows) *(planned)*                        |
| Gateway      | Spring Cloud Gateway *(planned)*                              |
| Containers   | Docker + docker-compose (database-per-service)                |

## Services

| Service             | Responsibility                                             | Status         |
| ------------------- | ---------------------------------------------------------- | -------------- |
| `patient-service`   | Core patient CRUD (REST + JPA + MariaDB); gRPC client      | ✅ Working     |
| `billing-service`   | Billing accounts + append-only ledger; gRPC server         | ✅ Working     |
| `analytics-service` | Consumes domain events from Kafka                          | 📋 Planned     |
| `auth-service`      | Authentication, JWT issuing & validation                   | 📋 Planned     |
| `api-gateway`       | Single entry point, routing to services                    | 📋 Planned     |

## Prerequisites

- **JDK 17** (the build is toolchain-pinned to 17)
- **Docker** & **Docker Compose** (runs the services + a MariaDB per service)
- **openssl** (only to generate dev mTLS certs — see below; present on macOS/Linux by default)

## Getting started

```bash
git clone <repo-url>
cd patient-management

# 1. Generate the dev mTLS certificates for the patient↔billing gRPC call (once).
#    Nothing cert-related is committed — this regenerates it. See "Security" below.
./generate-certs.sh

# 2. Bring up the whole stack (both services + a MariaDB container each).
docker compose up --build
#    patient-service  REST  http://localhost:4000
#    billing-service  REST  http://localhost:4001   gRPC :9001
```

Swagger UI per service at `/swagger-ui.html`; health at `/actuator/health`:

```
http://localhost:4000/swagger-ui.html      http://localhost:4000/actuator/health
http://localhost:4001/swagger-ui.html      http://localhost:4001/actuator/health
```

**End-to-end check:** `POST /api/v1/patients` on :4000 registers a patient; after the transaction
commits, patient-service calls billing's `OpenAccount` RPC over mTLS and a billing account is
opened (best-effort — a billing outage logs a warning but never fails registration).

### Running a single service locally (no Docker)

```bash
./generate-certs.sh                 # once, if you haven't
cd patient-service && ./mvnw spring-boot:run   # uses the host MariaDB via .env
```

### Tests

```bash
cd patient-service && ./mvnw test   # unit + web-slice + Testcontainers integration (needs Docker)
```

## Security — inter-service mTLS

The `patient → billing` gRPC call runs over **mutual TLS**: every call is encrypted *and* both
sides prove identity with certificates signed by a shared dev CA (the zero-trust baseline — never
trust the network).

- **No cert or key is committed.** `src/main/resources/certs/` is git-ignored in both services;
  `./generate-certs.sh` regenerates everything on demand into the same shared CA.
- **Only the CA *private key* is dangerous.** The committed-style artifacts (`ca-cert.pem` and the
  service certs) are *public* — they can only *verify*, never *sign*. The CA private key (which
  *could* mint trusted certs and impersonate a service) is generated in a temp dir and never
  distributed or stored.
- **Production** replaces all of this with a service mesh (Istio/SPIFFE) or Vault issuing and
  auto-rotating short-lived certs — the CA key lives in an HSM/Vault. Tracked in [`ROADMAP.md`](ROADMAP.md).

## Project layout

```
patient-management/
├── patient-service/     # core patient CRUD (REST) + gRPC client to billing   ← reference service
├── billing-service/     # billing accounts + ledger (REST) + gRPC server
├── analytics-service/   # planned — Kafka consumer
├── auth-service/        # planned — JWT issuing + validation
├── api-gateway/         # planned — Spring Cloud Gateway
├── docker-compose.yml   # orchestrates both services + a MariaDB each
├── generate-certs.sh    # regenerates the dev mTLS certs (run before build/run)
├── CLAUDE.md            # architecture & coding conventions (read before contributing)
├── ROADMAP.md          # planned hardening work
└── README.md
```

## Development conventions

Layered architecture per service (`model → repository → service → dto → mapper → controller →
exception`), rich domain model (no setters; behavior on entities), entity/DTO separation with
MapStruct, Flyway-owned schema, `@Transactional` service methods on InnoDB (ACID), optimistic
locking (`@Version`) with pessimistic write-locks on money movement, RFC 7807 `ProblemDetail`
errors, and structured JSON logging with correlation ids. Full details in [`CLAUDE.md`](CLAUDE.md).

## License

TBD
