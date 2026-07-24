package com.pm.patientservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.dto.PatientUpdateRequestDTO;
import com.pm.patientservice.repository.PatientRepository;
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

/**
 * End-to-end integration tests over real HTTP against a real MariaDB (Testcontainers). These
 * exercise what mocks cannot: Flyway migrations actually running, native {@code UUID} mapping,
 * the unique-email DB constraint, and the full optimistic-locking round-trip.
 */
@DisplayName("Patient API (integration)")
class PatientIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DOB = LocalDate.of(1990, 5, 10);

    @Autowired private TestRestTemplate rest;
    @Autowired private PatientRepository patientRepository;

    @BeforeEach
    void resetData() {
        patientRepository.deleteAll();
    }

    private PatientResponseDTO createAda() {
        PatientRequestDTO request =
                new PatientRequestDTO("Ada Lovelace", "ada@example.com", "12 Analytical St", DOB);
        ResponseEntity<PatientResponseDTO> created =
                rest.postForEntity("/patients", request, PatientResponseDTO.class);
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
                rest.getForEntity("/patients/{id}", PatientResponseDTO.class, id);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().email()).isEqualTo("ada@example.com");

        PatientUpdateRequestDTO update =
                new PatientUpdateRequestDTO("Ada L.", "ada@example.com", "New Address", DOB, 0L);
        ResponseEntity<PatientResponseDTO> updated = rest.exchange(
                "/patients/{id}", HttpMethod.PUT, new HttpEntity<>(update), PatientResponseDTO.class, id);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().version()).isEqualTo(1);
        assertThat(updated.getBody().address()).isEqualTo("New Address");

        ResponseEntity<Void> deleted =
                rest.exchange("/patients/{id}", HttpMethod.DELETE, null, Void.class, id);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> gone = rest.getForEntity("/patients/{id}", String.class, id);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("stale version update is rejected with 409")
    void staleVersionRejected() {
        UUID id = createAda().id();
        // first update takes it to version 1
        rest.exchange(
                "/patients/{id}",
                HttpMethod.PUT,
                new HttpEntity<>(new PatientUpdateRequestDTO("Ada", "ada@example.com", "A", DOB, 0L)),
                PatientResponseDTO.class,
                id);

        // second update with the now-stale version 0
        ResponseEntity<String> stale = rest.exchange(
                "/patients/{id}",
                HttpMethod.PUT,
                new HttpEntity<>(new PatientUpdateRequestDTO("Ada", "ada@example.com", "B", DOB, 0L)),
                String.class,
                id);

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("duplicate email is rejected with 409 (DB unique constraint path)")
    void duplicateEmailRejected() {
        createAda();
        ResponseEntity<String> dup = rest.postForEntity(
                "/patients",
                new PatientRequestDTO("Someone Else", "ada@example.com", "Elsewhere", DOB),
                String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("invalid body is rejected with 400")
    void invalidBodyRejected() {
        ResponseEntity<String> bad = rest.postForEntity(
                "/patients",
                new PatientRequestDTO("", "not-an-email", "x", LocalDate.now().plusYears(1)),
                String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("unknown id returns 404")
    void unknownIdReturns404() {
        ResponseEntity<String> missing =
                rest.getForEntity("/patients/{id}", String.class, UUID.randomUUID());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("unknown sort property returns 400, not 500")
    void unknownSortReturns400() {
        ResponseEntity<String> bad = rest.getForEntity("/patients?sort=bogus", String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
