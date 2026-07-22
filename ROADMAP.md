# Roadmap — hardening for scale

> **This is a backlog of *planned* work, not current conventions.** Nothing here is
> implemented yet. When an item is built, its rule graduates into [`CLAUDE.md`](CLAUDE.md) and
> the item is checked off here. Keeping the two separate keeps CLAUDE.md 100% true about what
> the code actually does.

Goal: a foundation fit for a platform used by millions — resilient, observable, and secure by
default. Grounded in current (2026) Spring Boot microservices best practice (see sources at
bottom).

## Tier 1 — strengthen `patient-service` (the reference service)

Cheap, high-impact; every future service inherits the pattern.

- [ ] **Automated tests** — Testcontainers integration tests (real MariaDB) + unit tests for
      the service layer. The single biggest gap today.
- [ ] **Optimistic locking** — `@Version` on entities to prevent lost updates under concurrent
      edits.
- [ ] **JPA auditing** — `createdAt` / `updatedAt` via a shared `BaseEntity`
      (`@EntityListeners(AuditingEntityListener.class)`). Mirror the `store` project's `BaseEntity`.
- [ ] **Soft delete** — patients (medical records) should not be hard-deleted; regulatory.
      Mirror `store`'s `SoftDeletableEntity`.
- [ ] **DB indexing** — index frequently queried columns beyond the unique `email`.

## Tier 2 — observability & ops (before traffic grows)

*"Introduce observability before traffic grows."*

- [ ] **Metrics** — `micrometer-registry-prometheus` → `/actuator/prometheus`.
- [ ] **Distributed tracing** — Micrometer Tracing + OpenTelemetry (OTLP), exported to Tempo.
- [ ] **Structured JSON logging** + correlation/trace IDs (`logstash-logback-encoder`).
- [ ] **K8s health probes** (liveness/readiness) + **graceful shutdown**
      (`server.shutdown=graceful`).

## Tier 3 — platform concerns (as services multiply)

- [ ] **Resilience4j** on inter-service calls — Circuit Breaker + Retry + TimeLimiter + Bulkhead
      (e.g. `patient → billing`).
- [ ] **API Gateway** (Spring Cloud Gateway) + **rate limiting** at the edge.
- [ ] **Config Server** + **Service Discovery** (or K8s-native).
- [ ] **Event-driven with Kafka** + **Outbox pattern** + **idempotent consumers** + **DLQ**.
- [ ] **Idempotency keys** on `POST` to dedupe retried creates.
- [ ] **Security** — `auth-service`, JWT / OAuth2 resource server, secrets in Vault, mTLS.
- [ ] **CI/CD** + containerization + **K8s manifests / Helm**.
- [ ] **Contract testing** (Spring Cloud Contract) between services.
- [ ] **Caching** (Redis) for read-heavy endpoints.

## Sources

- [Java Microservices Best Practices for Production 2026](https://gainjavaknowledge.medium.com/java-microservices-architecture-guide-spring-boot-best-practices-for-production-2026-e7c451b9d6f2)
- [Designing Resilient Microservices with Spring Boot](https://www.researchgate.net/publication/400371112_Designing_Resilient_Microservices_with_Spring_Boot_Fault_Tolerance_Circuit_Breakers_and_Observability)
- [Spring Boot Microservices at Scale: Reliability Lessons from Production](https://www.tymiq.com/post/spring-boot-microservices-lessons-from-real-projects)
- [Spring Cloud 2026: Resilience4j & Observability Guide](https://www.justacademy.co/blog-detail/microservices-with-spring-cloud-2026-service-mesh-resilience4j-observability)
- [Full Resilience4j guide for Spring Boot](https://dev.to/cryptodeploy/full-resiliency-guide-for-spring-boot-microservices-using-all-resilience4j-annotations-ljh)
