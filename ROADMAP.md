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
- [x] **Idempotency-Key** — two levels in `billing-service`. (1) **Domain** idempotency: a unique
      `idempotency_key` column per aggregate (ledger/transfer/payout), the correctness backstop that
      can never double-apply money. (2) **Generic HTTP layer**: an `@Idempotent` marker + a
      `HandlerInterceptor` that claims a per-user `(user_sub, id_key)` row, replays the stored response
      on a retry, and enforces the contract — `409` in-flight, `422` on key-reuse-with-different-body
      (SHA-256 fingerprint), `400` on a missing key; caches only final responses (2xx/4xx, never 5xx);
      24h TTL (hourly sweep) + a 60s claim lease so a dead request's key isn't wedged. The generic
      layer *complements* the domain keys, it doesn't replace them. Backport to patient `POST` if
      needed. `[#1]`

## Tier 2 — observability & ops (before traffic grows)

- [x] **Metrics** — `micrometer-registry-prometheus` → `/actuator/prometheus` on all four services,
      tagged `application=<service>`; a Prometheus container scrapes them. RED + JVM + HikariCP. `[#12]`
- [x] **Distributed tracing** — Micrometer Tracing + OpenTelemetry (OTLP) → **Jaeger** (single-container
      all-in-one; Tempo needs a separate Grafana UI, so Jaeger wins for local). Propagates the W3C
      `traceparent` across **HTTP** (auto), **gRPC** (net.devh 3.1.0 auto-instrumentation — the delete
      veto shows as one 3-service trace), and **Kafka** (producer `setObservationEnabled(true)` +
      `spring.kafka.listener.observation-enabled`). Verified end-to-end in Jaeger.
  - [x] **Outbox trace-continuity** (the seam above, now closed) — the `traceparent` is persisted on
        the `outbox_events` row at write time (V7) and restored in the relay before the send, so the
        publish→consume trace links back to the originating HTTP request. Verified in Jaeger:
        registering a patient is one trace across gateway → patient → (outbox-publish) → Kafka → billing.
  - [x] **Log ↔ trace correlation** — Micrometer puts `traceId`/`spanId` in the MDC; the ECS JSON logs
        emit them as the ECS-standard `trace.id`/`span.id` (via `logging.structured.json.rename`),
        alongside the existing `requestId`, so a log line pivots straight to its Jaeger trace.
- [x] **Structured JSON logging** + correlation IDs — native Boot structured logging (ECS) in prod on
      **all four services** (`docker` profile); `CorrelationIdFilter` → `X-Request-Id` in MDC on the
      servlet services, plus ECS `trace.id`/`span.id` everywhere for trace correlation. `[#59/#61]`
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
- [x] **Rate limiting** — Gateway `RequestRateLimiter` on every route, **Redis token bucket**
      (`RedisRateLimiter`, replenish/burst), keyed per user (`sub`) else client IP. State lives in Redis
      so the limit holds across (stateless) gateway replicas. Still to add: **load shedding** +
      stricter per-endpoint limits (e.g. tighter on `/login`). `[#2/#8]`

### Platform

- [~] **API Gateway** (Spring Cloud Gateway 5.0, reactive) — single entry point on `:4004`, routes
      to all services via the `RouteLocator` DSL. Done: routing + **edge JWT validation** (reactive
      `SecurityWebFilterChain` — reject unauthenticated at the door, forward the `Authorization`
      header; services still re-validate = defense in depth) + **Redis-backed rate limiting** (token
      bucket, per user/IP) + **CORS** (allowlist, wired into the security chain). Next: Config Server /
      service discovery, load shedding.
- [ ] **Config Server** + **Service Discovery** (or K8s-native).
- [x] **Containerization** — per-service multi-stage `Dockerfile` (non-root, healthcheck) + root
      `docker-compose.yml` (per-service DB). Done for `patient-service`.
- [ ] **CI/CD** + **K8s manifests/Helm**; blue-green / canary rollout. `[#91/#92]`
- [ ] **Load balancing + horizontal autoscaling** — L7 LB in front of each service's replicas (the
      gateway routes; the LB spreads load across pods); K8s **HPA** scales replica count on CPU/RPS.
      Cheap and effective *because* the services are stateless. `[#20]`
- [ ] **Contract testing** (Spring Cloud Contract) between services. `[#94]`
- [ ] **Platform e2e tests** — separate module booting gateway → patient → billing → Kafka.
      Introduce once a second service + gateway exist.

### Data & scale

> **See the [Scaling playbook](#scaling-playbook--the-lever-order-to-100m-users) below** for how
> these items sequence — pull them in cost order (index → replicas → cache → partition → shard),
> not all at once.

- [x] **Stateless services** — the enabler for everything else. All services are
      `SessionCreationPolicy.STATELESS` with JWTs verified locally against JWKS (no server session,
      no sticky sessions); all durable state lives in the DB / Kafka / outbox. So any replica serves
      any request → scale out by adding pods. *(The former blocker — the `@Scheduled OutboxRelay` —
      is now multi-instance-safe via `FOR UPDATE SKIP LOCKED`, so >1 patient-service replica is safe;
      see Event-driven.)*
- [ ] **Keyset (cursor) pagination** for large history endpoints (offset is fine for now). `[#24]`
- [ ] **Read/write splitting** across replicas — most traffic is reads; route `@Transactional(readOnly=true)`
      to replicas via `AbstractRoutingDataSource`, or transparently with **ShardingSphere-JDBC**. `[#20]`
- [ ] **Connection-pool tuning** (HikariCP) — pool size is a scale lever (and a footgun). The pool
      is the *cure* for "too many connections" (it caps + reuses), not the cause. Watch the math:
      `replicas × pool_max_size < max_connections − headroom(~10)`. Today: default pool 10 vs MariaDB
      `max_connections=151` → ~7% used, a non-issue until ~15 replicas of one service; DB-per-service
      isolates the blast radius, and `open-in-view=false` frees connections right after the service
      method. **Bigger pool ≠ better** — small pools (10–20) usually outperform large ones (the DB's
      concurrency is bounded); right-size, don't inflate. Note the pessimistic money-movement lock
      holds its connection for the whole txn. At high replica counts, add a **connection proxy**
      (ProxySQL) that multiplexes many app connections onto fewer DB ones; set `leakDetectionThreshold`.
- [ ] **CDN / edge caching** — front the gateway with a CDN (CloudFront/Cloudflare) for TLS
      termination, geo-routing, DDoS absorption, and edge-caching cacheable `GET`s (honoring the
      existing `ETag`/`Cache-Control`). PHI responses stay `no-store`; the win here is static/public
      assets, JWKS, and absorbing edge load — not caching patient data.
- [ ] **Caching** (Redis) — cache-aside for read-heavy endpoints + the id→display-name resolution.
      **PHI is not cached casually** (a Redis of names/DOBs is another PHI store — encrypt + TTL, or
      cache only ids/non-PHI). `[#38]`
- [ ] **Table partitioning** — range-partition append-only/time-series tables (`ledger_entries`,
      `outbox_events`) by month so old data is pruned/archived cheaply and hot queries scan less.
      Comes *before* sharding (single DB, no app changes).
- [ ] **Horizontal sharding** — the last DB lever. **Trigger is write-throughput-beyond-one-primary
      or hot-set-beyond-RAM, *not* row count** — a single indexed InnoDB node handles 100M–1B rows
      fine (an indexed lookup is ~3–5 page reads at 1M *or* 1B; row count barely moves it). Reads
      scale out with replicas + cache; only *writes* (which every replica must also apply) force
      sharding. So exhaust replicas → cache → partitioning → cold-data archival first; shard only when
      one primary genuinely can't absorb the write load or the working set won't fit RAM. DB-per-service
      already gives functional sharding; key-based sharding (e.g. by `patient_id`) via **Apache
      ShardingSphere-JDBC** (Spring Boot starter, app-transparent) or a **Vitess** proxy
      (MariaDB-compatible; sharding lives *below* JDBC, so Spring needs ~zero changes). UUID PKs
      already avoid global-sequence contention. `[#21]`
- [ ] **Ordered UUID (UUIDv7)** — switch PK generation to time-ordered UUIDs so inserts stay
      sequential in InnoDB's clustered index (random UUIDv4 causes page splits/write amplification at
      volume) — keeps the sharding-friendliness *and* insert locality.
- [ ] **Full-text / fuzzy search** (Elasticsearch/OpenSearch) — a *different* index type: an
      **inverted index** (term → documents), not a B-tree, built for relevance-ranked, typo-tolerant,
      partial-word search. **When we'd need it:** the B-tree `idx_patients_name` only serves
      exact/prefix match + sort (`LIKE 'sm%'` fast; `LIKE '%mit%'` and "did you mean *Smith*?" can't
      use it → full scan). The day patient lookup needs fuzzy/mid-word/multi-field ranked search, add
      a search index kept in sync off the **CDC / Kafka event stream** (search is a *read model*, never
      the source of truth — Postgres/MariaDB stays authoritative). Not before then — it's a whole
      component to run, and exact/prefix search doesn't justify it.
- [~] **CQRS read models** for reporting/statements. **Done:** `analytics-service` (`:4003`, own DB)
      is a dedicated read side — projects `patient-events` into denormalized read models
      (`daily_registrations` counter with a `processed_events` idempotency ledger; `active_patients`
      convergent set) and serves read-only query endpoints; a newly-added projection backfills via an
      admin **replay/rebuild** (seek the group to the topic start). **Next:** more read models
      (billing statements/reporting), and CDC-fed projections as volume grows. `[#54]`
- [ ] **Distributed locks** (Redlock/ZooKeeper) for singleton scheduled jobs (e.g. interest accrual). `[#90]`

### Event-driven & money (mostly `billing-service`)

- [x] **Kafka** (KRaft) + **Schema Registry** (Avro) + **Outbox pattern** + **idempotent consumers**
      + **DLQ** — registering a patient publishes `PatientRegistered` via a transactional outbox
      (`outbox_events` + `@Scheduled OutboxRelay`, at-least-once); `billing` consumes it and opens the
      account idempotently (duplicate → success), with `patient-events.DLT` for poison/exhausted
      records. Replaced the best-effort gRPC trigger. **kafka-ui** on `:8080` for inspection. `[#55/#52]`
  - **Multi-instance relay safety — done.** The relay claims each batch with a native
    `SELECT … FOR UPDATE SKIP LOCKED` (`lockUnpublishedBatch`), so relays on N replicas grab disjoint
    batches (skip, not block; no double-publish). Chosen over ShedLock (parallel vs. single-active, no
    new dependency). Correctness depends on `idx_outbox_unpublished` (else the DB filesorts and
    over-locks); proven by a concurrent-transaction integration test.
  - Still to layer on: **CDC (Debezium)** to replace the polling relay; an **outbox reaper**
    (purge/alert on old published/failed rows).
- [~] **Cross-service consistency** — "gate synchronously, propagate asynchronously." Patient
      deletion is a **synchronous gRPC veto** (`CloseAccountForPatient`): billing closes an empty
      account or vetoes a funded one (→ 409); `PatientDeleted` is fan-out only. *(An async
      choreographed **compensation saga** was built first — billing rejects → patient restores — then
      replaced by the sync veto for better UX; the flip-flop it removed and the history live in git.)*
      Money can't move on a non-`ACTIVE` account. **A same-DB money transfer is done as a local ACID
      transaction** (debit + credit + double-entry in one tx) — *not* a saga, because ACID spans both
      accounts; a saga would be over-engineering here. **The evolution path** (when a transfer gains a
      slow/external leg it can't span in one tx): `Transfer.status` goes `COMPLETED` → `PENDING` +
      async worker (kick it off reliably via the **outbox**, or poll `PENDING` rows with **SKIP
      LOCKED** — the DB as job queue; RabbitMQ only if you need delay/priority/DLQ) → **orchestrated
      saga** with a coordinator + compensation (`FAILED`/`REVERSED`). **That orchestrated saga is now
      built**, as a first-class **external payout** (`POST /billing-accounts/payouts`): the source is
      debited up front (`PENDING`); a **SKIP-LOCKED polling worker** (`PayoutSagaWorker`, mirroring the
      outbox relay) drives settlement through an `ExternalSettlementGateway` with capped exponential
      backoff; each outcome is terminal — `COMPLETED`, `REVERSED` (a *definitive* decline is
      **compensated** — the debit is credited back as a double-entry `<key>:reversal` leg), or `FAILED`
      (retries exhausted on *transient* errors — deliberately **not** auto-reversed: a lost ack could
      mean the money actually left, so a blind credit-back would double-pay). Each settlement step is an
      observed unit (`billing.payout.settle` span + `outcome`-tagged timer). Still to add: a
      **reconciliation job** — for the accepted register→delete race (empty orphaned account when async
      open lands after a sync delete), for `FAILED` payouts (query the rail's real status, then complete
      or reverse), and for the rare closed-account-mid-reversal edge; and **payout trace-continuity**
      (persist the initiate `traceparent` so the settlement span joins the originating request, like the
      outbox relay). `[#56]`
- [x] **Immutable ledger** for billing — append-only `ledger_entries` (each money movement with
      `balanceAfter`); full event-sourcing/double-entry is a later evolution. `[#53]`
- [ ] **CDC streaming** for reconciliation/reporting. `[#99]`

### Security (regulated domain — PHI / financial)

- [~] **`auth-service`** — JWT issuer (Spring Security 6): `/register` + `/login` (username-or-email,
      BCrypt, DB users), RSA/RS256 tokens, JWKS endpoint. **patient-service secured** as a resource
      server (validates against JWKS). **RBAC**, **billing secured**, **sync gRPC identity propagation**
      (patient→billing veto forwards the caller's `sub` as `x-actor-id` metadata), and **async
      actor-in-event** (`PatientRegistered` carries an `actor` field → billing audits the opened account
      as the registering user) all done — both cross-service boundaries now attribute the real user, not
      `"system"`. Next: **ABAC**/least-privilege. `[#27/#28/#29]`
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
- [~] **Double-entry / balanced ledger** — a **transfer** writes a matched DEBIT + CREDIT pair linked
      by `transfer_id`, so the ledger stays balanced. Still to generalize: every movement (not just
      transfers) as balanced entries, plus a trial-balance/invariant check. `[code]`
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

## Scaling playbook — the lever order (to 100M users)

> The trap in system-design tutorials is treating Redis + sharding as things you add *now*. The
> senior move is the opposite: **measure, then pull the cheapest lever that moves the metric.** Our
> architecture is already the right *foundation* (stateless services, DB-per-service, event-driven,
> idempotent consumers, UUID keys) — scaling is bolting techniques onto this skeleton in cost order,
> not a rewrite. The items are detailed under [Data & scale](#data--scale) / [Platform](#platform);
> this is the sequence to apply them.

Pull in this order (each step buys headroom; only advance when metrics say so):

1. **Index + query tuning** — verify with `EXPLAIN`. Cheapest, highest ROI. *(Already practiced —
   see Tier 1 DB indexing.)*
2. **Vertical scale** — bigger DB/pod. Boring, buys time, no code change.
3. **Read replicas** — most traffic is reads; split read/write. Single biggest DB win.
4. **Cache** (Redis) + **CDN/edge** — take read load off the DB and origin entirely.
5. **Stateless horizontal scale** — more service replicas behind an LB + HPA. *(Foundation already
   in place; unblock by making the outbox relay multi-instance-safe first.)*
6. **Table partitioning** — prune/archive time-series tables (ledger, outbox) within one DB.
7. **Horizontal sharding** — split the data tier by key (ShardingSphere/Vitess). Last, most-invasive;
   most systems never reach it because 1–6 suffice.

**Does Spring Boot "support" 100M-scale?** Spring stays deliberately *thin* here — scaling is a
data-tier + infra concern. Spring's role is routing (`AbstractRoutingDataSource`, ShardingSphere-JDBC
starter) and staying stateless so replicas are free; the heavy lifting (Vitess, replicas, CDN, HPA)
lives *below or around* the app, which is exactly why the app barely changes as you climb the tiers.

## Sources

- [Scalability — AlgoMaster (lever order: vertical/horizontal, replicas, sharding, cache/CDN)](https://algomaster.io/learn/system-design/scalability)
- [System Design: APIs, Databases, Caching, CDNs, Load Balancing & Production Infra](https://levelup.gitconnected.com/system-design-explained-apis-databases-caching-cdns-load-balancing-production-infra-81cddb7db3a7)
- [Scaling your Spring Boot app with ShardingSphere-JDBC (sharding + read/write splitting)](https://medium.com/@umeshcapg/a-guide-to-shardingsphere-jdbc-scaling-your-spring-boot-app-with-database-sharding-44e1ba0fa473)
- [A Guide to ShardingSphere — Baeldung](https://www.baeldung.com/java-shardingsphere)
- [Java Microservices Best Practices for Production 2026](https://gainjavaknowledge.medium.com/java-microservices-architecture-guide-spring-boot-best-practices-for-production-2026-e7c451b9d6f2)
- [Designing Resilient Microservices with Spring Boot](https://www.researchgate.net/publication/400371112_Designing_Resilient_Microservices_with_Spring_Boot_Fault_Tolerance_Circuit_Breakers_and_Observability)
- [Spring Boot Microservices at Scale: Reliability Lessons from Production](https://www.tymiq.com/post/spring-boot-microservices-lessons-from-real-projects)
- [Spring Cloud 2026: Resilience4j & Observability Guide](https://www.justacademy.co/blog-detail/microservices-with-spring-cloud-2026-service-mesh-resilience4j-observability)
- [Full Resilience4j guide for Spring Boot](https://dev.to/cryptodeploy/full-resiliency-guide-for-spring-boot-microservices-using-all-resilience4j-annotations-ljh)
