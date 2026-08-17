# Roadmap — hardening for scale

> **This is a backlog of *planned* work, not current conventions.** When an item is built, its
> rule graduates into [`CLAUDE.md`](CLAUDE.md) and the item is checked off here. Keeping the two
> separate keeps CLAUDE.md 100% true about what the code actually does.

Goal: a foundation fit for a platform used by millions — resilient, observable, and secure by
default. The domain is **regulated** (patient PHI today, financial-grade for `billing-service`),
so **auditability, correctness, and security are first-class**, not afterthoughts. `[#n]` tags
cross-reference the backend-patterns catalog we're prioritizing for banking/fintech readiness.

## Tier 1 — strengthen `patient-service` (cheap, high-impact; every service inherits it)

- [x] **Automated tests** — unit (Mockito) + web slice (`@WebMvcTest`) + integration
      (Testcontainers, real MariaDB). 27 green. Singleton-container base pattern.
- [x] **Optimistic locking** — `@Version` + client version on update, stale → 409 (Level 2). `[#79]`
- [x] **JPA auditing** — `createdAt`/`updatedAt` via a shared `BaseEntity` + `@EnableJpaAuditing`.
      (`createdBy`/`updatedBy` pending auth.) Regulated domains require an audit trail.
- [x] **Soft delete** — single nullable `deletedAt` (no boolean flag) via a reusable
      `SoftDeletableEntity` + domain `markDeleted()`; `@SQLRestriction("deleted_at is null")`
      filters reads; unique email stays locked (DB constraint → `DataIntegrityViolationException`
      → 409).
- [x] **DB indexing** — `idx_patients_name` on the default sort column; add composite indexes with
      the leftmost-prefix rule as query patterns emerge. `[#13/#16]`
- [x] **API versioning** — paths are under `/api/v1/...`. `[#62]`
- [x] **ETag / conditional requests** — `GET /{id}` returns `@Version` as the ETag; `If-None-Match`
      → 304. (Writes use body-version optimistic locking, not `If-Match`.) `[#44/#45]`
- [x] **Idempotency-Key** — done in `billing-service` credit/debit (required header, unique-key
      replay, never double-applies). Backport to patient `POST` if needed. `[#1]`

## Tier 2 — observability & ops (before traffic grows)

- [ ] **Metrics** — `micrometer-registry-prometheus` → `/actuator/prometheus`.
- [ ] **Distributed tracing** — Micrometer Tracing + OpenTelemetry (OTLP) → Tempo; replaces the
      hand-rolled `requestId` with propagated trace/span ids. `[#61]`
- [x] **Structured JSON logging** + correlation IDs — native Boot structured logging (ECS) in prod;
      `CorrelationIdFilter` → `X-Request-Id` in MDC. `[#59/#61]`
- [x] **Health probes** (liveness/readiness) + **graceful shutdown**. `[#65/#66]`
- [ ] **Log aggregation** — ship JSON logs to Loki/ELK, centrally indexed. `[#60]`

## Tier 3 — platform concerns (as services multiply)

### Inter-service communication (gRPC)

- [x] **gRPC `billing` `OpenAccount` server** — net.devh, mTLS. *Note:* this was originally the
      registration trigger (patient called it after commit); registration has since **moved to the
      Kafka/Outbox path** (Event-driven section). billing **keeps the gRPC server as a synchronous
      API** for future callers; the patient-side gRPC client was removed. `[#72]`
  - **net.devh `grpc-spring-boot-starter` 3.1.0** (not official Spring gRPC — its only Boot starter,
    1.0.3, is binary-incompatible with Boot 3.5; 1.1.0 hasn't shipped Boot starters). grpc 1.61 /
    protobuf 3.25, xolstice codegen.
  - Single **`.proto` contract**, **copied per service** (each generates its own stubs) to keep
    services independently buildable — deliberately *not* a shared module/reactor. Drift is caught
    by a **contract test**; a **Buf schema registry** is the scale evolution (single source with no
    build coupling). *(A shared module was tried and reverted — it broke independent Docker builds.)*
  - **Money as `string`** in proto (preserves `BigDecimal`). Server maps domain exceptions → gRPC
    **status codes** (`ALREADY_EXISTS`, `INVALID_ARGUMENT`) and reuses the existing service logic.
  - **mTLS** (dev certs) secures the server. The old client-side pieces (3s deadline, after-commit
    listener, best-effort fallback, Resilience4j) were removed when registration moved to Kafka —
    the **Outbox** now provides the guaranteed delivery those were approximating. History in git.

### Resilience

- [~] **Resilience4j** — was implemented on the `patient → billing` gRPC call (Circuit Breaker +
      Retry + fallback), then **removed** when that call was replaced by the Kafka/Outbox path
      (async delivery is guaranteed by the outbox, not by client-side resilience). Re-introduce when
      a **new synchronous** inter-service call needs it (e.g. a future gRPC query). The reasoning
      still holds: TimeLimiter only where an async/`Future` return exists; Bulkhead where concurrency
      isn't already bounded. `[#7/#11/#12]`
- [ ] **Rate limiting** (token bucket) at the gateway + **load shedding**. `[#2/#8]`

### Platform

- [~] **API Gateway** (Spring Cloud Gateway 5.0, reactive) — single entry point on `:4004`, routes
      to all services via the `RouteLocator` DSL. Done: routing. Next: edge JWT validation, rate
      limiting, CORS.
- [ ] **Config Server** + **Service Discovery** (or K8s-native).
- [x] **Containerization** — per-service multi-stage `Dockerfile` (non-root, healthcheck) + root
      `docker-compose.yml` (per-service DB). Done for `patient-service`.
- [ ] **CI/CD** + **K8s manifests/Helm**; blue-green / canary rollout. `[#91/#92]`
- [ ] **Contract testing** (Spring Cloud Contract) between services. `[#94]`
- [ ] **Platform e2e tests** — separate module booting gateway → patient → billing → Kafka.
      Introduce once a second service + gateway exist.

### Data & scale

- [ ] **Keyset (cursor) pagination** for large history endpoints (offset is fine for now). `[#24]`
- [ ] **Read/write splitting** across replicas. `[#20]`
- [ ] **Caching** (Redis) — cache-aside for read-heavy endpoints. `[#38]`
- [ ] **CQRS read models** for reporting/statements. `[#54]`
- [ ] **Distributed locks** (Redlock/ZooKeeper) for singleton scheduled jobs (e.g. interest accrual). `[#90]`

### Event-driven & money (mostly `billing-service`)

- [x] **Kafka** (KRaft) + **Schema Registry** (Avro) + **Outbox pattern** + **idempotent consumers**
      + **DLQ** — registering a patient publishes `PatientRegistered` via a transactional outbox
      (`outbox_events` + `@Scheduled OutboxRelay`, at-least-once); `billing` consumes it and opens the
      account idempotently (duplicate → success), with `patient-events.DLT` for poison/exhausted
      records. Replaced the best-effort gRPC trigger. **kafka-ui** on `:8080` for inspection. `[#55/#52]`
  - Still to layer on: **CDC (Debezium)** to replace the polling relay; **multi-instance relay
    safety** (`SELECT … FOR UPDATE SKIP LOCKED` or ShedLock — ties into Distributed locks below);
    an **outbox reaper** (purge/alert on old published/failed rows).
- [~] **Cross-service consistency** — "gate synchronously, propagate asynchronously." Patient
      deletion is a **synchronous gRPC veto** (`CloseAccountForPatient`): billing closes an empty
      account or vetoes a funded one (→ 409); `PatientDeleted` is fan-out only. *(An async
      choreographed **compensation saga** was built first — billing rejects → patient restores — then
      replaced by the sync veto for better UX; the flip-flop it removed and the history live in git.)*
      Money can't move on a non-`ACTIVE` account. Still to add: an **orchestrated** saga with a
      coordinator (e.g. transfer = debit + credit with rollback) for a genuinely multi-step
      transaction; a **reconciliation job** for the accepted register→delete race (empty orphaned
      account when async open lands after a sync delete). `[#56]`
- [x] **Immutable ledger** for billing — append-only `ledger_entries` (each money movement with
      `balanceAfter`); full event-sourcing/double-entry is a later evolution. `[#53]`
- [ ] **CDC streaming** for reconciliation/reporting. `[#99]`

### Security (regulated domain — PHI / financial)

- [~] **`auth-service`** — JWT issuer (Spring Security 6): `/register` + `/login` (username-or-email,
      BCrypt, DB users), RSA/RS256 tokens, JWKS endpoint. **patient-service secured** as a resource
      server (validates against JWKS). Done. Next: **RBAC** (`@PreAuthorize` + map the `roles` claim),
      secure **billing** + service-to-service token propagation, **ABAC**/least-privilege. `[#27/#28/#29]`
- [~] **Zero-trust inter-service security** (never trust the network; encrypt + authenticate +
      authorize *every* call): `[#33]`
  - [x] **mTLS** between services — app-level mTLS on the patient↔billing gRPC call (dev
        self-signed certs). No cert material is committed: `certs/` is git-ignored and
        `./generate-certs.sh` regenerates a shared CA + per-service certs on demand (same posture
        as prod — regenerate per environment). Production moves this into a **service mesh
        (Istio/Linkerd)** where sidecars do mTLS transparently with **auto-rotating short-lived
        certs**; the CA private key lives in an HSM/Vault.
  - [ ] **Workload identity (SPIFFE/SPIRE)** — cryptographic per-service identity for authz.
  - [ ] **AuthorizationPolicy** — which caller may invoke which RPC (mesh-level RBAC).
  - [ ] **NetworkPolicies** (K8s) — restrict pod-to-pod; **TLS 1.3** at the edge.
- [ ] **Secrets / cert management** (Vault) — no committed keys; rotation.
- [ ] **Field-level encryption at rest** for PII/PHI (email, DOB, address). `[#34]`
- [ ] **HMAC request signing** for partner/webhook APIs. `[#97]`

## Tier 4 — bank-grade: financial correctness, compliance & operations

The layer *above* backend patterns. `[code]` = a senior engineer owns it in-service; `[org]` =
platform/compliance teams own it, but design compatibly and be able to speak to it.

### Financial correctness (mostly `billing-service`)

- [x] **Money as `BigDecimal` — never `double`/`float`** — `billing-service` balance is
      `DECIMAL(19,2)` with a currency. Explicit rounding modes land with credit/debit. `[code]`
- [ ] **Double-entry / balanced ledger** — every debit has a matching credit. `[code]`
- [ ] **Reconciliation** jobs (end-of-day, vs external systems) + **FX / multi-currency**. `[code]`
- [ ] **Transaction limits & velocity checks** (fraud/AML gating). `[code]`

### Compliance & data governance

- [ ] **Immutable audit trail of *who* did *what*** — beyond `createdAt`/`updatedAt`. `[code]`
- [ ] **Log redaction / PII-PHI masking** — never log PANs, passwords, PHI. Near-term: we now
      emit logs, so guarantee sensitive fields never reach them. `[code]`
- [ ] **Data retention & right-to-erasure** (GDPR/DPA) via tokenization / crypto-shredding. `[code/org]`
- [ ] **KYC/AML hooks, consent management, regulatory reporting**. `[org]`

### Security depth

- [ ] **KMS/HSM key management** + **secrets rotation** (not just Vault storage). `[org]`
- [ ] **Tokenization** of sensitive data (PAN/account). `[code/org]`
- [ ] **Fraud/anomaly detection**, **MFA**, **DDoS protection** (above app rate limiting). `[org]`

### Operational maturity / reliability

- [ ] **Zero-downtime expand/contract migrations** — Flyway changes stay backward-compatible so
      rolling deploys never break. Adopt now while migrations are simple. `[code]`
- [ ] **Load / performance testing** + **chaos testing**. `[code/org]`
- [ ] **DR (RTO/RPO), multi-region HA, backups + point-in-time recovery**. `[org]`
- [ ] **SLO/SLA + error budgets, runbooks, on-call**. `[org]`

### Integration governance

- [ ] **Webhook delivery with retries + signing**, **API deprecation policy**, **per-client/tenant
      SLAs**, **sandbox environments**. `[code/org]`

## Sources

- [Java Microservices Best Practices for Production 2026](https://gainjavaknowledge.medium.com/java-microservices-architecture-guide-spring-boot-best-practices-for-production-2026-e7c451b9d6f2)
- [Designing Resilient Microservices with Spring Boot](https://www.researchgate.net/publication/400371112_Designing_Resilient_Microservices_with_Spring_Boot_Fault_Tolerance_Circuit_Breakers_and_Observability)
- [Spring Boot Microservices at Scale: Reliability Lessons from Production](https://www.tymiq.com/post/spring-boot-microservices-lessons-from-real-projects)
- [Spring Cloud 2026: Resilience4j & Observability Guide](https://www.justacademy.co/blog-detail/microservices-with-spring-cloud-2026-service-mesh-resilience4j-observability)
- [Full Resilience4j guide for Spring Boot](https://dev.to/cryptodeploy/full-resiliency-guide-for-spring-boot-microservices-using-all-resilience4j-annotations-ljh)
