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
| Containers | Docker + docker-compose per environment |

## Repository layout

```
patient-management/
├── patient-service/     # core patient CRUD (REST + JPA + MariaDB)   ← reference service
├── billing-service/     # (planned) gRPC billing, called by patient-service
├── analytics-service/   # (planned) Kafka consumer, event-driven
├── auth-service/        # (planned) JWT issuing + validation
├── api-gateway/         # (planned) Spring Cloud Gateway, single entry point
└── infrastructure/      # (planned) shared docker-compose (DBs, Kafka)
```

## Per-service package structure (layered)

```
com.pm.<service>/
├── model/        # JPA entities — persistence only, no validation annotations
├── repository/   # Spring Data JPA interfaces
├── service/      # business logic — INTERFACE + impl, @Transactional on writes
├── dto/          # request/response objects — the API contract, carries Bean Validation
├── mapper/       # entity <-> DTO conversion
├── controller/   # REST endpoints — HTTP concerns only
└── exception/    # custom exceptions + @RestControllerAdvice global handler
```

## Conventions (enforce these)

- **Entity vs DTO split**: entities model the table (`@Column` constraints only); DTOs carry
  Bean Validation (`@NotBlank`, `@Email`, ...). **Never expose entities in controllers.**
- **Naming**: Java fields are `camelCase`; DB columns are `snake_case`. Hibernate's default
  `CamelCaseToUnderscoresNamingStrategy` bridges them (`dateOfBirth` → `date_of_birth`).
  Flyway SQL therefore uses snake_case column names.
- **Primary keys**: `UUID` with `@GeneratedValue(strategy = GenerationType.UUID)`. Stored as
  `BINARY(16)` in MariaDB — Flyway column types must match.
- **Schema authority is Flyway**, not Hibernate. `spring.jpa.hibernate.ddl-auto=validate`
  (or `none`) in every service. Migrations live in `src/main/resources/db/migration/`,
  named `V<n>__<description>.sql`.
- **ACID**: all tables `ENGINE=InnoDB`. Service write-methods are `@Transactional`.
- **SOLID where it earns its keep**:
  - SRP — one responsibility per layer (controller ≠ service ≠ repository).
  - DIP/OCP — controllers depend on service *interfaces*; Spring injects the impl.
  - ISP — repositories stay focused.
- Server-set fields (e.g. `registeredDate`) are populated in the service, never accepted
  from the client.
- **Documentation**: comment the *why*, not the *what*. Javadoc on public API (service
  interfaces, custom exceptions, non-obvious contracts) + "why" notes where reality is
  surprising. No comments that restate code, no commented-out code, rely on clear names.

## Build & run

```bash
# per service
cd patient-service
./mvnw compile          # compile
./mvnw test             # run tests
./mvnw spring-boot:run  # run locally
```

## Status

- [x] `patient-service` scaffolded (Boot 3.5.16, MariaDB, Flyway, deps verified)
- [x] `Patient` entity (UUID id, snake_case columns, InnoDB-bound)
- [ ] `application.yml` + docker-compose (MariaDB)
- [ ] Flyway `V1__create_patients_table.sql`
- [ ] repository → service → DTO/validation → controller → exception handler
- [ ] remaining services + gateway
