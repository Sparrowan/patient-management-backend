# Patient Management — Microservices

A production-grade Spring Boot microservices system for managing patients, billing, and
analytics. Each service is an **independent, standalone Spring Boot application** (its own
`pom.xml`, its own build, its own database) — the true microservices model, not a shared
multi-module reactor build.

## Tech stack

| Concern | Choice |
|---|---|
| Language / JDK | Java 17 |
| Framework | Spring Boot **3.5.x** (latest stable 3.x — deliberately *not* 4.x, for Spring Cloud ecosystem compatibility) |
| Build | Maven (per-service `pom.xml`, inheriting `spring-boot-starter-parent`) |
| Database | **MariaDB** (InnoDB engine — required for ACID), one DB per service |
| Migrations | **Flyway** (`flyway-core` + `flyway-mysql`) — schema is versioned SQL, never `ddl-auto` |
| Validation | Jakarta Bean Validation — on **DTOs**, not entities |
| Mapping | **MapStruct** (`@Mapper`) — compile-time, `unmappedTargetPolicy=ERROR` |
| Boilerplate | **Lombok** (`@RequiredArgsConstructor` for injection) |
| API docs | **springdoc-openapi** — Swagger UI, annotated endpoints |
| Errors | RFC 7807 `ProblemDetail` via `@RestControllerAdvice` |
| Containers | Docker + docker-compose per environment |

## Repository layout

```
patient-management/
├── patient-service/     # core patient CRUD (REST + JPA + MariaDB)   ← reference service
├── billing-service/     # billing accounts + ledger; gRPC server called by patient-service
├── auth-service/        # JWT issuer: /login + /register, RSA-signed tokens, JWKS endpoint (:4002)
├── api-gateway/         # Spring Cloud Gateway — single entry point, routes to all services (:4004)
├── docker-compose.yml   # root orchestration: every service + a DB container each + Kafka/SR
└── analytics-service/   # (planned) Kafka consumer, event-driven
```

**Ports:** gateway `:4004` (the single entry point clients use), patient `:4000`, billing `:4001`
(+ gRPC `:9001`), auth `:4002`; kafka-ui `:8080`, schema-registry `:8081`.

Each service holds its own copy of `billing.proto` under `src/main/proto/` and generates its own
stubs — a deliberate trade-off to keep services independently buildable (no shared module / no
reactor). Drift between copies is caught by a contract test (see ROADMAP).

## Per-service package structure (layered)

```
com.pm.<service>/
├── model/        # JPA entities — persistence only, no validation annotations
├── repository/   # Spring Data JPA interfaces
├── service/      # business logic — INTERFACE + impl, @Transactional on writes
├── dto/          # request/response objects — the API contract, carries Bean Validation
├── mapper/       # entity <-> DTO conversion
├── controller/   # REST endpoints — HTTP concerns only
├── config/       # Spring @Configuration beans (OpenAPI, etc.)
├── web/          # servlet filters / cross-cutting web concerns (correlation id)
└── exception/    # custom exceptions + @RestControllerAdvice global handler
```

## Conventions (enforce these)

- **Entity vs DTO split**: entities model the table (`@Column` constraints only); DTOs carry
  Bean Validation (`@NotBlank`, `@Email`, ...). **Never expose entities in controllers.**
- **Naming**: Java fields are `camelCase`; DB columns are `snake_case`. Hibernate's default
  `CamelCaseToUnderscoresNamingStrategy` bridges them (`dateOfBirth` → `date_of_birth`).
  Flyway SQL therefore uses snake_case column names.
- **Table names are plural, entity classes singular** (`Patient` → `@Table(name = "patients")`).
  Plural sidesteps SQL reserved-word collisions (`user`/`order`) and reads naturally.
- **Primary keys**: `UUID` with `@GeneratedValue(strategy = GenerationType.UUID)`. On MariaDB
  10.7+ this maps to the **native `UUID` column type** (what Hibernate's MariaDBDialect
  validates against) — Flyway migrations use `id UUID`, not `BINARY(16)`.
- **Schema authority is Flyway**, not Hibernate. `spring.jpa.hibernate.ddl-auto=validate`
  (or `none`) in every service. Migrations live in `src/main/resources/db/migration/`,
  named `V<n>__<description>.sql`.
- **ACID**: all tables `ENGINE=InnoDB`. Service write-methods are `@Transactional`.
- **Optimistic concurrency (Level 2)**: entities carry `@Version`; update DTOs require the
  client's `version` and responses expose it. The service rejects a stale version with
  `ObjectOptimisticLockingFailureException` → 409. Use `saveAndFlush` on update so the response
  returns the *incremented* version (plain `save` flushes at commit, after mapping).
- **Locking**: optimistic (`@Version`) is the default everywhere. **Money-movement paths take a
  pessimistic write lock** (`@Lock(PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE`) on the account
  so concurrent credits/debits serialize instead of 409-ing on the version check.
- **Relationships & N+1**: prefer **ID references** (a `UUID` field + DB FK) over JPA associations
  across aggregates — no lazy navigation, no N+1 (e.g. `LedgerEntry.accountId`, not
  `@ManyToOne`). Where an association *is* modeled, keep it `LAZY` (override `@ManyToOne`'s EAGER
  default) and fetch on demand with `@EntityGraph`/`JOIN FETCH` — **never `EAGER`**.
  `open-in-view=false` everywhere.
- **`BaseEntity` mapped superclass** carries the cross-cutting persistence fields: `@CreatedDate`
  `createdAt` / `@LastModifiedDate` `updatedAt` (via `@EnableJpaAuditing`) + `@Version`. Every
  entity extends it. `createdBy`/`updatedBy` come once an `AuditorAware` (auth) exists.
- **Soft delete** is opt-in via a reusable `SoftDeletableEntity extends BaseEntity`. A single
  nullable `deletedAt` is the whole state (null = live; `isDeleted()` derives from it — no
  redundant boolean). A domain `markDeleted()` stamps it; the service saves (a normal UPDATE, so
  auditing fires). Each concrete entity adds `@SQLRestriction("deleted_at is null")` itself
  (Hibernate applies it per-entity, not via the superclass). The row (and its unique email) is
  retained, so emails are **not** reusable — a re-create hits the DB constraint →
  `DataIntegrityViolationException` → 409. Append-only entities (e.g. a ledger) do not extend it.
  Tests hard-`TRUNCATE` between cases (not `deleteAll()`, which only soft-deletes).
- **API paths are versioned** under `/api/v1/...` so the contract can evolve without breaking
  clients.
- **ETag / conditional reads**: `GET /{id}` returns the entity `@Version` as the ETag;
  `If-None-Match` → 304. Writes use body-version optimistic locking (not `If-Match`).
- **Never log PII/PHI** (names, emails, DOB, addresses). Logs carry ids + the correlation id only.
- **SOLID where it earns its keep**:
  - SRP — one responsibility per layer (controller ≠ service ≠ repository).
  - DIP/OCP — controllers depend on service *interfaces*; Spring injects the impl.
  - ISP — repositories stay focused.
- **Rich domain model, not anemic**: entities own their state changes through
  intention-revealing behavior (static factory for creation, e.g. `Patient.register(...)`;
  named mutators, e.g. `updateDetails(...)`). No public setters; JPA no-arg constructor is
  `protected`. Creation goes through the domain factory, so mappers have `toResponse` only,
  no `toEntity`. Apply where it earns its keep — don't push infrastructure or cross-aggregate
  logic into entities.
- Server-set fields (e.g. `registeredDate`) are an invariant the entity stamps at creation,
  never accepted from the client.
- **Documentation**: comment the *why*, not the *what*. Javadoc on public API (service
  interfaces, custom exceptions, non-obvious contracts) + "why" notes where reality is
  surprising. No comments that restate code, no commented-out code, rely on clear names.
- **DTOs are `record`s**; entity↔DTO mapping is a MapStruct `@Mapper` interface (never
  hand-written). Use real types (`UUID`, `LocalDate`) in DTOs — Jackson serializes them and
  MapStruct maps 1:1 with no conversion.
- **Constructor injection via Lombok `@RequiredArgsConstructor`** on `final` fields — no
  hand-written constructors, no field `@Autowired`.
- **Lombok on entities: `@Getter` only.** Never `@Setter` (would break the rich model) and
  never `@Data` (its toString/equals touch lazy state and violate JPA identity). DTOs are
  `record`s, so they need no Lombok at all.
- **Build**: use `./mvnw` (runs on JDK 17). Lombok + MapStruct processors are declared as
  provided-scope deps; `~/.m2/toolchains.xml` (JDK 17) guards against the system `mvn`
  running on Java 26.
- **Config externalization (12-factor)**: externalize only what *changes between environments*
  (DB URL/creds, `SPRING_PROFILES_ACTIVE`, service addresses like `BILLING_GRPC_ADDRESS`) via
  `${VAR:default}` placeholders. *Invariant/behavioral* config (`ddl-auto=validate`, Flyway
  locations, pagination caps, `open-in-view=false`, mTLS `client-auth=REQUIRE`) stays hardcoded
  in `application.properties` — moving it to env vars only adds risk (e.g. a stray
  `ddl-auto=create-drop` in prod) for no benefit. `.env` is a **local-dev convenience only**
  (loaded by spring-dotenv, git-ignored); prod injects real env vars via the orchestrator and
  secrets via Vault/k8s Secrets — never a committed file. Every externalized var is documented
  in the committed `.env.example`, each with a safe default.

### REST / API conventions

- **Controllers do HTTP concerns only**: bind, validate, delegate, return a DTO. No business
  logic, no `if/else` on error conditions. Return the DTO directly (not `ResponseEntity`)
  unless headers are needed.
- **Status codes**: happy-path only in the controller — default `200`, or
  `@ResponseStatus(CREATED)` for `POST` (`201`), `@ResponseStatus(NO_CONTENT)` for `DELETE`
  (`204`). Error codes come from the exception handler, never hand-branched.

  | Op | Success | Errors |
  |----|---------|--------|
  | `GET` list | 200 | — |
  | `GET /{id}` | 200 | 404 |
  | `POST` | 201 | 400, 409 |
  | `PUT /{id}` | 200 | 400, 404, 409 |
  | `DELETE /{id}` | 204 | 404 |

- **Errors are centralized**: one `@RestControllerAdvice` (extends
  `ResponseEntityExceptionHandler` so framework errors are ProblemDetail too) maps domain
  exceptions (`*NotFoundException` → 404, `*AlreadyExistsException` → 409, validation → 400) to
  RFC 7807 `ProblemDetail`, with a logged catch-all → 500 that leaks no internals. Services throw
  domain exceptions; they never return null/empty for "not found".
- **Observability (three pillars + health)**: **health** = Actuator liveness/readiness probes +
  graceful shutdown. **Logs** = `CorrelationIdFilter` puts an `X-Request-Id` in the MDC per request
  (echoed in the response header); native structured JSON (ECS) in the `docker`/prod profile, plain
  console + `requestId` locally. The active `traceId`/`spanId` ride in the MDC too, emitted as
  ECS-standard `trace.id`/`span.id` in the JSON logs, so a log line pivots straight to its Jaeger
  trace. **Metrics** = Micrometer → `/actuator/prometheus` (RED + JVM +
  HikariCP), tagged `application=<service>`, scraped by a Prometheus container. **Traces** = Micrometer
  Tracing → OTLP → Jaeger; the W3C `traceparent` propagates across **HTTP** (auto), **gRPC** (net.devh
  auto-instrumentation) and **Kafka** (producer/consumer observation). Known seam: the outbox relay
  publishes on a scheduled thread, so the Kafka trace roots at the relay tick, not the originating
  request — trace-continuity via a stored `traceparent` is a planned follow-up (see ROADMAP).
- **Request DTOs are validated**: `record` request DTOs carry Bean Validation
  (`@NotBlank`, `@Email`, ...); controllers annotate the body with `@Valid`.
- **List endpoints are paginated**: accept `Pageable`; never return an unbounded `findAll()`
  over a growable table. Annotate the `Pageable` param with springdoc's `@ParameterObject` so
  Swagger renders `page`/`size`/`sort` as query params (not a bogus JSON body). An unknown
  `sort` property throws `PropertyReferenceException` → mapped to 400 in the global handler.
- **Every endpoint is documented** with springdoc OpenAPI (`@Operation`, `@ApiResponse`,
  `@Tag`) so `/swagger-ui.html` stays complete.

### Event-driven / messaging conventions

- **Two transports, on purpose**: **gRPC** for *synchronous* command/query between two services
  (billing keeps an `OpenAccount` gRPC server for future sync callers); **Kafka** for *asynchronous*
  event broadcast. Registering a patient is async → Kafka, not gRPC.
- **Transactional Outbox for reliable publishing**: never dual-write (save to DB *then* publish —
  a crash between them loses the event). The event is written to an `outbox_events` row **in the
  same transaction** as the business change; a `@Scheduled` **`OutboxRelay`** ships unpublished rows
  to Kafka and stamps `published_at`. Delivery is **at-least-once**; combined with an idempotent
  consumer it's effectively exactly-once. Relay claims each batch with a native **`SELECT … FOR
  UPDATE SKIP LOCKED`** (`OutboxEventRepository.lockUnpublishedBatch`), so it's **multi-instance
  safe**: N relays across N replicas each grab a *disjoint* batch (a second relay skips the locked
  rows, never blocks or double-publishes) and the claim is held until the tx commits. Correct
  row-precise locking depends on the `idx_outbox_unpublished` index — without it the DB filesorts and
  over-locks the batch. CDC (Debezium) is the further scale evolution — see ROADMAP.
- **Avro + Schema Registry** is the wire contract. The `.avsc` is **copied per service** (same
  independent-services trade-off as `billing.proto`); each generates its own class via
  `avro-maven-plugin` (`stringType=String`). Confluent images run **KRaft** (no ZooKeeper).
- **No PHI on the wire**: events carry ids + a currency + timestamps — never name/email/DOB (the
  same rule as logging, applied to events). Payloads stamp an `eventId` at creation (stable across
  relay retries → the consumer's idempotency key).
- **Idempotent consumers**: rely on a natural unique key where one exists (opening an account is
  keyed on `patientId`; a duplicate throws `AccountAlreadyExistsException`, which the listener
  treats as **success** and never rethrows — so a redelivery is not dead-lettered).
- **DLQ, not poison-loop**: consumers use `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`
  → `<topic>.DLT` after a bounded retry. `ErrorHandlingDeserializer` wraps the Avro deserializer so
  an undeserializable record is dead-lettered immediately (non-retryable) instead of jamming the
  partition. The recoverer routes by value type (Avro object vs raw bytes) via a two-template map.
- **Cross-service consistency: gate synchronously, propagate asynchronously** (never cascade
  deletes). ACID stops at the service boundary (separate DBs, no cross-DB FK). Split each
  cross-service concern by what it needs:
  - **A user-facing veto** ("may this action proceed?") is a **synchronous gRPC command**, owned by
    the service that holds the data — immediate, authoritative, strongly consistent. Deleting a
    patient calls billing's `CloseAccountForPatient` (mTLS gRPC): billing closes an empty account, or
    returns `FAILED_PRECONDITION` for a funded one → patient returns **409** (settle first); billing
    unreachable → **503** (fail safe — block the delete). The veto runs *before* the local
    soft-delete, so a rejection leaves the patient untouched — no delete-then-restore flip-flop.
  - **Consequences / fan-out** ("X happened, others may react") stay **asynchronous events**.
    Registering a patient is async (outbox → `PatientRegistered` → billing opens the account —
    decoupled, resilient); `PatientDeleted` is emitted **fan-out only** (analytics/audit/future) —
    billing no-ops on it.
  - **Rule of thumb:** one synchronous gate → **sync command**; multi-step / long-running / many
    gates → **async orchestrated saga** with a visible "pending" state. *Never* implement a
    user-facing veto via async choreography + compensation — it flip-flops on every rejection.
  - **Seams are explicit, not hidden:** the sync veto has rare edges (a fast register→delete race
    can leave an empty orphaned account because open is async; a local failure right after billing
    closed) — **accepted + reconciled** (see ROADMAP), never papered over. Money never moves on a
    non-`ACTIVE` account (`AccountNotActiveException` → 409).
  - **Event ordering:** all of an aggregate's lifecycle events share **one topic** keyed by the
    aggregate id (`patient-events`) — Kafka orders only within a topic-partition. Multiple schemas on
    one topic need `TopicRecordNameStrategy`; the consumer is a class-level `@KafkaListener` with a
    `@KafkaHandler` per type. Next: an *orchestrated* saga (coordinator + rollback) for a genuinely
    multi-step transaction.

## Build & run

Each service has its own multi-stage `Dockerfile`; the root `docker-compose.yml` orchestrates
services + a per-service DB container.

```bash
docker compose up --build   # whole stack; patient-service on :4000, its own MariaDB container
docker compose down         # stop (add -v to wipe the DB volume)
```

Local dev without Docker: `./mvnw spring-boot:run` (uses the host MariaDB via `.env`). The
Dockerfile skips tests (they need Docker for Testcontainers — run them in CI / `./mvnw test`).

**Build gotcha (IDE):** VSCode's Java language server (Eclipse JDT) shares `target/classes`
with Maven and, if its project model goes stale after a POM change, compiles the MapStruct
mapper with unresolved imports and clobbers Maven's output (`java.lang.Error: Unresolved
compilation problems` / `No qualifying bean of type PatientMapper` at startup). Guarded by
`java.autobuild.enabled: false` in `.vscode/settings.json`. If it recurs, run
`Java: Clean Java Language Server Workspace` and reload.

## Status

- [x] `patient-service` scaffolded (Boot 3.5.16, MariaDB, Flyway, deps verified)
- [x] `Patient` entity (UUID id, snake_case columns, InnoDB-bound)
- [x] MapStruct + Lombok + records wired
- [x] Full CRUD: repository → service (interface+impl) → DTOs/validation → controller
- [x] Global exception handler (`ProblemDetail`), pagination, OpenAPI/Swagger
- [x] Config (`application.properties` + `.env` via spring-dotenv), local MariaDB `patient_db`
- [x] Flyway `V1__create_patients_table.sql` (native `UUID`, InnoDB)
- [x] Booted & verified end-to-end (201/200/400/404/409, Swagger, DB persistence)
- [x] Tests: unit (Mockito) + web slice (`@WebMvcTest`) + integration (Testcontainers MariaDB), 27 green
- [x] Optimistic locking (`@Version`, Level 2)
- [x] Auditing `BaseEntity` (createdAt/updatedAt), soft-delete (`@SoftDelete`, locked email → 409)
- [x] API versioning (`/api/v1`), ETag/304 conditional reads, `idx_patients_name`
- [x] Observability: structured JSON logs (ECS), `CorrelationIdFilter`, catch-all → 500, graceful shutdown
- [x] Dockerized (multi-stage image + compose w/ per-service DB)
- [x] `billing-service` scaffolded (Boot 3.5.16) + domain slice: `BillingAccount` (money as
      `DECIMAL(19,2)`), open/read endpoints, same conventions as patient-service, 13 tests green
- [x] `billing-service` money movement: credit/debit + append-only ledger + `Idempotency-Key`
      (unique-key replay), insufficient-funds → 422, money never rounded (`@Digits`), 30 tests
- [x] `billing-service` dockerized (multi-stage image + `billing-service-db` in root compose);
      both services + their DBs come up with `docker compose up --build`. 60 tests green total.
- [x] gRPC `billing` server (net.devh) secured with **mTLS** (dev certs, zero cert material
      committed — `./generate-certs.sh` regenerates a shared CA + per-service certs; `certs/`
      git-ignored). Exposes `OpenAccount` and **`CloseAccountForPatient`** (the deletion veto —
      patient-service is a gRPC **client** again for this synchronous call).
- [x] **Kafka event backbone** (KRaft) + **Confluent Schema Registry** + **Avro** contracts +
      **kafka-ui**. Registering a patient now publishes `PatientRegistered` via the **Transactional
      Outbox** (`outbox_events` + `@Scheduled OutboxRelay`, at-least-once); `billing` consumes it
      (`@KafkaListener`), opens the account **idempotently** (duplicate → success, no DLQ), with a
      **DLQ** (`patient-events.DLT`) on exhausted retries / poison messages.
- [x] Outbox→Kafka→billing flow **verified end-to-end in Docker** (register → account opened;
      idempotent redelivery = no-op, no DLQ).
- [x] **Deletion = synchronous gRPC veto** (`CloseAccountForPatient`, mTLS): billing closes an empty
      account, or `FAILED_PRECONDITION` for a funded one → patient returns **409** (settle first);
      billing down → **503**. Runs before the soft-delete (no flip-flop). `PatientDeleted` is now
      **fan-out only** (billing no-ops). Money can't move on a non-`ACTIVE` account (409). *(This
      replaced an async compensation saga — "gate sync, propagate async"; the saga history lives in git.)*
- [x] **auth-service** (`:4002`) — Spring Security 6 JWT issuer. `/register` + `/login` (login by
      username **or** email; BCrypt; DB-backed `UserDetailsService`), RSA-signed (RS256) tokens via
      `NimbusJwtEncoder`, public keys served at `/oauth2/jwks`. Users in MariaDB (`auth_db`, Flyway).
- [x] **patient-service secured as a resource server** — `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`
      → auth's JWKS; `/api/**` requires a valid JWT (verified locally, stateless), `/actuator/**` +
      swagger stay public. Tests: `@WithMockUser` (web slice), mocked `JwtDecoder` + a real 401 test.
- [x] **api-gateway** (`:4004`, Spring Cloud Gateway 5.0 / Spring Cloud 2025.0, reactive) — single
      entry point, `RouteLocator` DSL routes `/api/v1/{auth,patients,billing-accounts}/**` + `/oauth2/jwks`
      to each service. Full stack (10 containers) runs behind it; login→token→access verified end-to-end.
- [x] **RBAC** — a `JwtAuthenticationConverter` maps our custom `roles` claim (space-separated,
      already `ROLE_`-prefixed → empty authority prefix) to authorities; `@EnableMethodSecurity` +
      `@PreAuthorize("hasRole('ADMIN')")` makes **deleting a patient admin-only** (`ROLE_USER` → 403).
      *Gotcha:* `@PreAuthorize` throws inside the servlet, so the global advice must map
      `AccessDeniedException` → 403 or the catch-all turns it into 500 (401 is filter-level, unaffected).
- [x] **JPA auditing "who"** (patient **and** billing) — an `AuditorAware<String>` reads the JWT `sub`
      from the SecurityContext (`"system"` for background writes: Kafka consumer/gRPC/schedulers);
      `@CreatedBy`/`@LastModifiedBy` on `BaseEntity` stamp `created_by`/`updated_by` (patient `V6`,
      billing `V3`). Billing's `LedgerEntry` extends `BaseEntity`, so **every money movement records
      who performed it**. The token `sub` is the user's **UUID**, not the username — a stable,
      never-reassigned id so the audit reference doesn't rot on a rename (OIDC); a separate
      `preferred_username` claim carries the display name (`AppUserDetails` threads the id into `TokenService`).
- [x] **billing-service secured as a resource server** — same JWKS-validating, stateless setup as
      patient; money movement (credit/debit) is **admin-only** (`@PreAuthorize`), so a frontend can
      safely read account data while writes stay gated.
- [x] **api-gateway edge JWT validation** — reactive `SecurityWebFilterChain`/`ServerHttpSecurity`
      (the WebFlux mirror of the servlet resource-server config): unauthenticated requests are rejected
      at `:4004` before routing (auth routes + JWKS + actuator stay public), and the `Authorization`
      header is forwarded so **services still re-validate** (defense in depth). Edge does authN;
      per-role authZ (`@PreAuthorize`) stays at the services.
- [x] **Service-to-service identity propagation (sync gRPC)** — the patient→billing deletion veto now
      forwards the caller's `sub` as an `x-actor-id` gRPC metadata header (client interceptor reads the
      `SecurityContext`; server interceptor binds it to the gRPC `Context`); billing's `AuditorAware`
      resolves **REST principal → gRPC actor → Kafka actor → `"system"`**. So closing an account during a
      delete is audited as the real user (`updated_by` = their UUID), verified end-to-end. Only the id is
      sent, not the token — the mTLS channel already authenticates the caller and billing makes no authZ
      decision on it (trusted identity assertion, not credential relay).
- [x] **Actor-in-event (async Kafka)** — the counterpart for the async boundary: the `PatientRegistered`
      Avro event carries an `actor` field (added **with a default** → backward-compatible schema evolution)
      holding the registering user's `sub`, captured at registration. Billing's consumer binds it to a
      `ConsumerActorContext` (a `ThreadLocal` — a Kafka handler runs synchronously on one thread, so no
      cross-thread `Context` needed) around the account-open, cleared in `finally`. `created_by` on the
      opened account is now the real user, not `"system"` — verified end-to-end. You **never** propagate a
      live token over Kafka (the event may be consumed/replayed after the token expires); the durable
      *fact* of who acted rides as event data.
- [x] **Gateway rate limiting + CORS** — Spring Cloud Gateway's `RequestRateLimiter` on every route,
      backed by a **Redis token bucket** (`RedisRateLimiter`: replenish 10/s, burst 20; state in Redis
      *because* the gateway is stateless, so the limit holds across replicas). Keyed **per user (`sub`)
      when authenticated, else client IP** (brute-force cover where there's no principal). **CORS** is
      owned by the gateway (single entry point) and wired **into** the reactive security chain so the
      preflight `OPTIONS` is answered before the token check; `allowCredentials=true` so origins are an
      explicit allowlist, never `*`. Verified live: 429 on burst (IP and per-user), preflight allow/deny.
- [x] **Multi-instance-safe outbox relay** — the relay claims its batch with a native
      `SELECT … FOR UPDATE SKIP LOCKED` (`lockUnpublishedBatch`), so N patient-service replicas each
      run a relay that grabs a *disjoint* batch — no double-publish, no blocking — which unblocks
      horizontal scale-out (the last thing that made the relay single-instance). Proven by a
      concurrent-transaction integration test; SKIP LOCKED correctness relies on the
      `idx_outbox_unpublished` index (else the DB over-locks). *(Chose SKIP LOCKED over ShedLock:
      both relays work in parallel vs. only one active; no extra dependency.)*
- [x] **Money transfer (first-class `Transfer` aggregate)** — `POST /api/v1/billing-accounts/transfers`,
      admin-only, `Idempotency-Key`. A **local ACID** transaction (both accounts in one DB — deliberately
      *not* a saga): **double-entry** (linked DEBIT+CREDIT ledger legs sharing a `transfer_id`),
      transfer-level idempotency (unique key + replay), and the two concurrency concerns a single
      credit/debit lacks — **deadlock-safe lock ordering** (lock both accounts by id order, so opposing
      `A→B`/`B→A` transfers can't cycle) and **`READ_COMMITTED` isolation** (MariaDB's default REPEATABLE
      READ + `SELECT … FOR UPDATE` throws error 1020 "record has changed" under concurrent modification;
      READ COMMITTED locks the latest committed row). `TransferStatus` is a seam: a same-DB transfer is
      always `COMPLETED`; a future slow/external transfer becomes `PENDING` + async worker, then an
      orchestrated saga (`FAILED`/`REVERSED` compensation) — see ROADMAP.
- [x] **Observability Tier 2 — metrics + tracing.** **Prometheus metrics** on all four services
      (`/actuator/prometheus`, RED + JVM + HikariCP, tagged `application=<service>`, scraped by a
      Prometheus container). **Distributed tracing** (Micrometer Tracing → OTLP → **Jaeger**): the W3C
      `traceparent` propagates across **HTTP** (auto), **gRPC** (net.devh 3.1.0 auto-instrumentation —
      the delete veto shows as one 3-service trace) and **Kafka** (producer `setObservationEnabled` +
      `spring.kafka.listener.observation-enabled`). Verified end-to-end in Jaeger. Known seam: the
      outbox relay publishes on a `@Scheduled` thread, so the Kafka trace roots at the relay tick, not
      the originating request — outbox trace-continuity (store+restore `traceparent`) is the next bit.
      Docker builds also cache the Maven repo via a BuildKit mount (only changed deps re-download).
- [ ] Next: orchestrated saga (the async/cross-boundary transfer evolution), CDC (Debezium),
      reconciliation. Background writes with no originating user (schedulers) still audit `"system"`.

**gRPC note:** uses **net.devh `grpc-spring-boot-starter` 3.1.0** on both sides, NOT the official
`org.springframework.grpc` — its only published Boot starter (1.0.3) is binary-incompatible with
Boot 3.5 (references a removed `PropertyMapper$Source$Adapter`), and 1.1.0 ships no Boot starter
yet. Pinned to **grpc 1.61.1 / protobuf 3.25.5** (net.devh's runtime) so the xolstice-generated
stubs match; `javax.annotation-api` is a provided dep for the generated `@Generated`. The `billing.proto`
contract is **copied into each service** (`src/main/proto/`) and each generates its own stubs —
a deliberate independent-services trade-off (no shared module / reactor); drift is caught by a
contract test (see ROADMAP). Billing runs a gRPC server on **:9001** alongside REST **:4001**.

**gRPC is secured with mTLS** (encrypted + both sides authenticate via certs — the zero-trust
baseline). Dev self-signed certs live in each service's `src/main/resources/certs/` (a CA + a
per-service cert; SANs cover `localhost` and `billing-service`). Server: `grpc.server.security.*`
with `client-auth=REQUIRE`. Client: `grpc.client.billing.security.*` — **must set
`client-auth-enabled=true`** or the client cert is configured but never presented (→
`TLSV1_ALERT_CERTIFICATE_REQUIRED`). **Private keys (`*-key.pem`) are git-ignored.** Production
moves mTLS into a service mesh (Istio/SPIFFE) with auto-rotating certs — see ROADMAP.
- [ ] remaining services + gateway
