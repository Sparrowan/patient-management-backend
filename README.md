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
                        ┌──────────────────────┐
   client  ──────────▶  │  api-gateway  :4004  │   single entry point (Spring Cloud Gateway)
                        └───────────┬──────────┘   routes /api/v1/{auth,patients,billing-accounts}
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
     ┌──────────────┐      ┌───────────────┐      ┌───────────────┐
     │ auth-service │      │patient-service│      │billing-service│
     │  :4002       │      │  REST :4000   │      │  REST :4001   │
     │ /login /reg  │      │  resource     │      │  gRPC :9001   │ (mTLS)
     │ RS256 + JWKS │◀─────│  server (JWT) │      │  resource srv │
     └──────────────┘ JWKS └──────┬────────┘      └───────────────┘
       verify tokens              │   REGISTER ─async(outbox,Avro)─▶ opens account
                                  │   DELETE ─ sync gRPC veto ─────▶ 409 if funded
                                  └─ PatientDeleted (fan-out only)

  AUTH:     login at the gateway → RS256 JWT; every service validates it locally against auth's JWKS.
  REGISTER: async event (guaranteed via outbox), decoupled/resilient.
  DELETE:   synchronous gRPC veto — immediate 409 if the account is funded (no flip-flop).
  Kafka (KRaft) + Schema Registry · kafka-ui :8080 · patient-events (+ .DLT) · DB per service
```

**Gate synchronously, propagate asynchronously.** The user-facing **veto** (may this patient be
deleted?) is a **synchronous mTLS gRPC** call — immediate, authoritative, 409 if the account holds
funds. The **fan-out** (registration opening an account; `PatientDeleted` for analytics/audit) stays
**asynchronous** over Kafka, guaranteed via the transactional outbox.

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
| Messaging    | **Kafka** (KRaft) + **Confluent Schema Registry** + **Avro**  |
| Reliability  | **Transactional Outbox** (guaranteed publish) + consumer **DLQ** |
| Sync RPC     | gRPC (billing `OpenAccount` server), secured with **mTLS**    |
| Security     | **JWT** (RS256) — auth-service issues, services validate as **resource servers** (JWKS) |
| Gateway      | **Spring Cloud Gateway** 5.0 (reactive) — single entry point `:4004` |
| Containers   | Docker + docker-compose (database-per-service)                |

## Services

| Service             | Responsibility                                             | Status         |
| ------------------- | ---------------------------------------------------------- | -------------- |
| `api-gateway`       | Single entry point (:4004), routes to all services         | ✅ Working     |
| `auth-service`      | JWT issuer: /login + /register, RS256, JWKS (:4002)        | ✅ Working     |
| `patient-service`   | Patient CRUD (REST); outbox producer; JWT resource server  | ✅ Working     |
| `billing-service`   | Billing accounts + ledger; Kafka consumer; gRPC server     | ✅ Working     |
| `analytics-service` | Consumes domain events from Kafka                          | 📋 Planned     |

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

# 2. Bring up the whole stack (all services + a MariaDB each + Kafka + Schema Registry + kafka-ui).
docker compose up --build
#    api-gateway            http://localhost:4004   ← the single entry point clients use
#    auth-service           http://localhost:4002   (also via the gateway)
#    patient / billing      :4000 / :4001 (+ gRPC :9001) — reachable through the gateway
#    kafka-ui               http://localhost:8080   (topics, messages, Avro schemas, DLQ)
#    schema-registry        http://localhost:8081
```

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

**End-to-end check:** `POST /api/v1/patients` (with the bearer token) registers a patient. In the
*same* transaction an event is written to the `outbox_events` table; the `OutboxRelay` publishes it
to the Kafka topic `patient-events` (Avro), and billing-service consumes it and opens the account —
**guaranteed** (survives a billing outage) and **idempotent** (a redelivery is a no-op).

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
```

## Security — inter-service mTLS

billing's **gRPC server** runs over **mutual TLS**: every call is encrypted *and* both sides prove
identity with certificates signed by a shared dev CA (the zero-trust baseline — never trust the
network). Registration itself now flows over Kafka; the gRPC server is kept for future synchronous
callers, which is why the mTLS setup remains.

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
├── patient-service/     # patient CRUD (REST) + transactional outbox → Kafka   ← reference service
├── billing-service/     # billing accounts + ledger (REST) + Kafka consumer + gRPC server
├── analytics-service/   # planned — Kafka consumer
├── auth-service/        # planned — JWT issuing + validation
├── api-gateway/         # planned — Spring Cloud Gateway
├── docker-compose.yml   # both services + a MariaDB each + Kafka + Schema Registry + kafka-ui
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
