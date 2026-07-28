package com.pm.patientservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.dto.PatientUpdateRequestDTO;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end integration tests over real HTTP against a real MariaDB (Testcontainers). These
 * exercise what mocks cannot: Flyway migrations, native {@code UUID} + auditing mapping, the
 * unique-email DB constraint (incl. the soft-delete lock), and the full optimistic-locking
 * round-trip.
 */
@DisplayName("Patient API (integration)")
class PatientIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/patients";
    private static final LocalDate DOB = LocalDate.of(1990, 5, 10);

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetData() {
        // Hard truncate — deleteAll() would only *soft*-delete, leaving rows (and locked emails)
        // behind and breaking isolation between tests.
        jdbcTemplate.execute("TRUNCATE TABLE patients");
    }

    private PatientResponseDTO createAda() {
        PatientRequestDTO request =
                new PatientRequestDTO("Ada Lovelace", "ada@example.com", "12 Analytical St", DOB);
        ResponseEntity<PatientResponseDTO> created =
                rest.postForEntity(BASE, request, PatientResponseDTO.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }

    @Test
    @DisplayName("full lifecycle: create → read → update → delete → gone")
    void fullLifecycle() {
        PatientResponseDTO created = createAda();
        assertThat(created.id()).isNotNull();
        assertThat(created.version()).isZero();
        UUID id = created.id();

        ResponseEntity<PatientResponseDTO> fetched =
                rest.getForEntity(BASE + "/{id}", PatientResponseDTO.class, id);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().email()).isEqualTo("ada@example.com");
        assertThat(fetched.getHeaders().getETag()).isEqualTo("\"0\"");

        PatientUpdateRequestDTO update =
                new PatientUpdateRequestDTO("Ada L.", "ada@example.com", "New Address", DOB, 0L);
        ResponseEntity<PatientResponseDTO> updated = rest.exchange(
                BASE + "/{id}", HttpMethod.PUT, new HttpEntity<>(update), PatientResponseDTO.class, id);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().version()).isEqualTo(1);
        assertThat(updated.getBody().address()).isEqualTo("New Address");

        ResponseEntity<Void> deleted =
                rest.exchange(BASE + "/{id}", HttpMethod.DELETE, null, Void.class, id);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> gone = rest.getForEntity(BASE + "/{id}", String.class, id);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("stale version update is rejected with 409")
    void staleVersionRejected() {
        UUID id = createAda().id();
        rest.exchange(
                BASE + "/{id}",
                HttpMethod.PUT,
                new HttpEntity<>(new PatientUpdateRequestDTO("Ada", "ada@example.com", "A", DOB, 0L)),
                PatientResponseDTO.class,
                id);

        ResponseEntity<String> stale = rest.exchange(
                BASE + "/{id}",
                HttpMethod.PUT,
                new HttpEntity<>(new PatientUpdateRequestDTO("Ada", "ada@example.com", "B", DOB, 0L)),
                String.class,
                id);

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("duplicate email is rejected with 409")
    void duplicateEmailRejected() {
        createAda();
        ResponseEntity<String> dup = rest.postForEntity(
                BASE,
                new PatientRequestDTO("Someone Else", "ada@example.com", "Elsewhere", DOB),
                String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("email stays locked after a soft delete: re-create is rejected with 409")
    void softDeletedEmailIsLocked() {
        UUID id = createAda().id();
        rest.exchange(BASE + "/{id}", HttpMethod.DELETE, null, Void.class, id);

        // The soft-deleted patient is gone from reads...
        assertThat(rest.getForEntity(BASE + "/{id}", String.class, id).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // ...but its email still occupies the unique index, so re-creating it conflicts.
        ResponseEntity<String> recreate = rest.postForEntity(
                BASE,
                new PatientRequestDTO("Ada Again", "ada@example.com", "Somewhere", DOB),
                String.class);
        assertThat(recreate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("invalid body is rejected with 400")
    void invalidBodyRejected() {
        ResponseEntity<String> bad = rest.postForEntity(
                BASE,
                new PatientRequestDTO("", "not-an-email", "x", LocalDate.now().plusYears(1)),
                String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("unknown id returns 404")
    void unknownIdReturns404() {
        ResponseEntity<String> missing =
                rest.getForEntity(BASE + "/{id}", String.class, UUID.randomUUID());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("unknown sort property returns 400, not 500")
    void unknownSortReturns400() {
        ResponseEntity<String> bad = rest.getForEntity(BASE + "?sort=bogus", String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
