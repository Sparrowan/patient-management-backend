# Patient Management

A production-grade **Spring Boot microservices** system for managing patients, billing, and
analytics. Each service is an **independent, standalone Spring Boot application** — its own
build, its own database, its own deployable — following the true microservices model rather
than a shared multi-module build.

> **Contributing / conventions:** architecture rules, layering, and coding standards live in
> [`CLAUDE.md`](CLAUDE.md). Read it before adding a service or opening a PR. Planned hardening
> work (resilience, observability, security, scale) is tracked in [`ROADMAP.md`](ROADMAP.md).
> A whole-system diagram lives in [`design.drawio`](design.drawio) (opens in draw.io or the
> VS Code extension).

## Architecture

```
                          ┌───────────────────────────┐
   client  ───────────▶   │   api-gateway      :4004  │  single entry point
                          │   routing · edge JWT      │  (Spring Cloud Gateway, reactive)
                          │   rate limit · CORS       │
                          └─────────────┬─────────────┘
          ┌─────────────┬───────────────┼───────────────┐
          ▼             ▼               ▼               ▼
   ┌────────────┐ ┌───────────────┐ ┌──────────────┐ ┌──────────────┐
   │auth  :4002 │ │patient  :4000 │ │billing :4001 │ │analytics:4003│
   │/login /reg │ │REST + outbox  │ │REST + ledger │ │CQRS read side│
   │RS256 +JWKS │◀│resource server│ │gRPC :9001 ⇄  │ │query-only    │
   └────────────┘ └───────┬───────┘ └──────┬───────┘ └──────┬───────┘
     verify tokens        │                │                │
                    ┌─────▼─────┐    ┌─────▼─────┐    ┌─────▼─────┐
                    │patient_db │    │billing_db │    │analytics_ │
                    │ + replica │    │           │    │   db      │
                    └───────────┘    └───────────┘    └───────────┘

   REGISTER : patient ─async (outbox → Kafka, Avro)──▶ billing opens the account
   DELETE   : patient ─sync gRPC veto (mTLS)────────▶ 409 if the account is funded
   PROJECT  : analytics consumes patient-events into its own read models
   Redis    : gateway rate-limit buckets + patient cache-aside
   Kafka (KRaft) + Schema Registry · patient-events (+ .DLT) · database per service
```

**Gate synchronously, propagate asynchronously.** The user-facing **veto** (may this patient be
deleted?) is a **synchronous mTLS gRPC** call — immediate, authoritative, 409 if the account holds
funds. The **fan-out** (registration opening an account; `PatientDeleted` for analytics/audit) stays
**asynchronous** over Kafka, guaranteed via the transactional outbox.

## Tech stack

| Concern        | Choice                                                                                          |
|----------------|-------------------------------------------------------------------------------------------------|
| Language       | Java 17                                                                                         |
| Framework      | Spring Boot 3.5.x                                                                               |
| Build          | Maven (per-service `pom.xml`)                                                                   |
| Database       | MariaDB (InnoDB, ACID), one database per service + a **read replica** for patient               |
| Migrations     | Flyway (versioned SQL, never `ddl-auto`)                                                        |
| Mapping        | MapStruct (compile-time) + Lombok + Java `record` DTOs                                          |
| API docs       | springdoc-openapi (Swagger UI)                                                                  |
| Messaging      | **Kafka** (KRaft) + **Confluent Schema Registry** + **Avro**                                    |
| Reliability    | **Transactional Outbox** (guaranteed publish) + consumer **DLQ** + **idempotency** (two levels) |
| Sync RPC       | gRPC (billing `OpenAccount`, `CloseAccountForPatient`), secured with **mTLS**                   |
| Security       | **JWT** (RS256) — auth-service issues, services validate as **resource servers** (JWKS)         |
| Gateway        | **Spring Cloud Gateway** 5.0 (reactive) — single entry point `:4004`                            |
| Cache / limits | **Redis** — cache-aside on patient reads, token-bucket rate limiting at the edge                |
| Observability  | **Prometheus** metrics · **Jaeger** tracing (OTLP) · structured ECS JSON logs                   |
| Containers     | Docker + docker-compose (database-per-service)                                                  |

## Services

| Service             | Responsibility                                                              | Status    |
|---------------------|-----------------------------------------------------------------------------|-----------|
| `api-gateway`       | Single entry point (:4004) — routing, edge JWT, rate limiting, CORS         | ✅ Working |
| `auth-service`      | JWT issuer: `/login` + `/register`, RS256, JWKS (:4002)                     | ✅ Working |
| `patient-service`   | Patient CRUD (REST); outbox producer; read/write split; JWT resource server | ✅ Working |
| `billing-service`   | Accounts, ledger, transfers, payout saga; Kafka consumer; gRPC server       | ✅ Working |
| `analytics-service` | CQRS read side — projects `patient-events` into read models (:4003)         | ✅ Working |

## What's interesting in here

Beyond CRUD, the parts worth reading:

- **Transactional outbox** — no dual write. The event is written in the same transaction as the
  business change; a relay ships it with `SELECT … FOR UPDATE SKIP LOCKED`, so N replicas claim
  disjoint batches and the relay scales horizontally.
- **Money movement** — an append-only double-entry ledger, pessimistic write locks on the account,
  a same-DB ACID `Transfer`, and an **orchestrated payout saga** (reserve → settle or compensate)
  for money leaving to an external rail.
- **Idempotency at two levels** — a generic `@Idempotent` HTTP layer that replays stored responses,
  sitting *on top of* per-aggregate unique keys, which remain the correctness backstop for money.
- **CQRS** — `analytics-service` has no command API. Its read models are projected from the event
  log and can be rebuilt from scratch by replaying it.
- **Data-tier scaling** — read/write splitting to a real replica, Redis cache-aside, keyset
  pagination, and hot/cold ledger archival that bounds the working set on a table you may never
  delete from.

## Prerequisites

- **JDK 17** (the build is toolchain-pinned to 17)
- **Docker** & **Docker Compose** (runs the services + a MariaDB per service)
- **openssl** (only to generate dev mTLS certs — see below; present on macOS/Linux by default)

## Getting started

```bash
git clone <repo-url>
cd patient-management

# 1. Generate the dev mTLS certificates for billing's gRPC server (once).
#    Nothing cert-related is committed — this regenerates it. See "Security" below.
./generate-certs.sh

# 2. Bring up the whole stack (every service + a MariaDB each + Kafka, Redis, Prometheus, Jaeger).
docker compose up --build
```

| URL                      | What                                                       |
|--------------------------|------------------------------------------------------------|
| `http://localhost:4004`  | **api-gateway** — the single entry point clients use       |
| `http://localhost:4000`  | patient-service (direct; normally reached via the gateway) |
| `http://localhost:4001`  | billing-service (direct) — gRPC on `:9001`                 |
| `http://localhost:4002`  | auth-service (direct)                                      |
| `http://localhost:4003`  | analytics-service (direct)                                 |
| `http://localhost:8080`  | kafka-ui — topics, messages, Avro schemas, DLQ             |
| `http://localhost:8081`  | schema-registry                                            |
| `http://localhost:9090`  | Prometheus                                                 |
| `http://localhost:16686` | Jaeger — distributed traces                                |

Everything is reached through the **gateway on :4004**. The APIs require a JWT — get one by logging
in (the seeded dev admin is `admin` / `password`):

```bash
# 1. Log in through the gateway → access token
TOKEN=$(curl -s -X POST http://localhost:4004/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"admin","password":"password"}' | jq -r .accessToken)

# 2. Call a protected API through the gateway (401 without the token, 200 with it)
curl -H "Authorization: Bearer $TOKEN" http://localhost:4004/api/v1/patients
```

> The gateway rate-limits per user (or per IP when anonymous). That is deliberate, and it means
> **load tests must target a service directly**, not `:4004` — otherwise you are measuring the
> limiter. See [`loadtest/`](loadtest/).

**End-to-end check:** `POST /api/v1/patients` (with the bearer token) registers a patient. In the
*same* transaction an event is written to the `outbox_events` table; the `OutboxRelay` publishes it
to the Kafka topic `patient-events` (Avro); billing-service consumes it and opens the account, and
analytics-service projects it into its read models — **guaranteed** (survives an outage) and
**idempotent** (a redelivery is a no-op).

`DELETE /api/v1/patients/{id}` first makes a **synchronous mTLS gRPC** call to billing
(`CloseAccountForPatient`): a **funded** account → **409** (settle the balance first, patient *not*
deleted); an **empty** account → billing closes it and the patient is soft-deleted; billing down →
**503**. So the loop is: *credit an account → DELETE → 409 → debit to zero → DELETE → 204 (closed).*
A `PatientDeleted` event is still emitted as fan-out (visible in kafka-ui at `http://localhost:8080`).

### Running a single service locally (no Docker)

```bash
./generate-certs.sh                 # once, if you haven't
cd patient-service && ./mvnw spring-boot:run   # uses the host MariaDB via .env
```

### Tests

```bash
cd patient-service && ./mvnw test   # unit + web-slice + Testcontainers integration (needs Docker)
cd billing-service && ./mvnw test
```

> **Stop the `billing-service` container first.** Its tests start a real gRPC server on `:9001`,
> which the running container already holds — the context fails to load with `Address already in use`.

### Load testing

Scripts and findings live in [`loadtest/`](loadtest/). The short version: on an 8-core laptop the
platform ceiling is ~2,300 req/s for an endpoint that does *nothing*, and a real JWT-verified,
cache-backed read reaches ~78% of that — so the application is cheap and the host is the
constraint. Absolute numbers from that environment describe the laptop, not the system; only
relative comparisons at fixed concurrency mean anything there.

## Security — inter-service mTLS

billing's **gRPC server** runs over **mutual TLS**: every call is encrypted *and* both sides prove
identity with certificates signed by a shared dev CA (the zero-trust baseline — never trust the
network).

- **No cert or key is committed.** `src/main/resources/certs/` is git-ignored in both services;
  `./generate-certs.sh` regenerates everything on demand into the same shared CA.
- **Only the CA *private key* is dangerous.** The committed-style artifacts (`ca-cert.pem` and the
  service certs) are *public* — they can only *verify*, never *sign*. The CA private key (which
  *could* mint trusted certs and impersonate a service) is generated in a temp dir and never
  distributed or stored.
- **Identity propagates across the boundary.** The caller's `sub` travels as gRPC metadata on the
  synchronous path and as an `actor` field on the async event, so `created_by`/`updated_by` record
  the real user rather than `"system"`. A live token is never put on Kafka — an event may be
  replayed long after it expires.
- **Production** replaces all of this with a service mesh (Istio/SPIFFE) or Vault issuing and
  auto-rotating short-lived certs — the CA key lives in an HSM/Vault. Tracked in [`ROADMAP.md`](ROADMAP.md).

## Project layout

```
patient-management/
├── api-gateway/         # Spring Cloud Gateway — single entry point, edge JWT, rate limit, CORS
├── auth-service/        # JWT issuer (RS256) + JWKS endpoint
├── patient-service/     # patient CRUD (REST) + transactional outbox → Kafka   ← reference service
├── billing-service/     # accounts, ledger, transfers, payout saga + Kafka consumer + gRPC server
├── analytics-service/   # CQRS read side — projects patient-events into read models
├── loadtest/            # k6 scripts + measured findings (see "Load testing")
├── docker-compose.yml   # every service + a MariaDB each + Kafka, Redis, Prometheus, Jaeger
├── generate-certs.sh    # regenerates the dev mTLS certs (run before build/run)
├── design.drawio        # whole-system architecture diagram
├── CLAUDE.md            # architecture & coding conventions (read before contributing)
├── ROADMAP.md           # planned hardening work
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
