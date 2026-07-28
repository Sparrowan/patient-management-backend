package com.pm.billingservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.OpenAccountRequestDTO;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end integration tests over real HTTP against a real MariaDB (Testcontainers). Exercises
 * Flyway, native UUID + DECIMAL money mapping, and the unique-per-patient constraint.
 */
@DisplayName("Billing API (integration)")
class BillingAccountIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/billing-accounts";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("TRUNCATE TABLE billing_accounts");
    }

    @Test
    @DisplayName("open → read by id → read by patient, with a zero BigDecimal balance")
    void openAndRead() {
        UUID patientId = UUID.randomUUID();
        ResponseEntity<BillingAccountResponseDTO> opened = rest.postForEntity(
                BASE, new OpenAccountRequestDTO(patientId, "USD"), BillingAccountResponseDTO.class);

        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(opened.getBody().balance()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(opened.getBody().currency()).isEqualTo("USD");
        UUID id = opened.getBody().id();

        assertThat(rest.getForEntity(BASE + "/{id}", BillingAccountResponseDTO.class, id).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<BillingAccountResponseDTO> byPatient =
                rest.getForEntity(BASE + "/by-patient/{pid}", BillingAccountResponseDTO.class, patientId);
        assertThat(byPatient.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byPatient.getBody().id()).isEqualTo(id);
    }

    @Test
    @DisplayName("a second account for the same patient is rejected with 409")
    void duplicatePatientRejected() {
        UUID patientId = UUID.randomUUID();
        rest.postForEntity(BASE, new OpenAccountRequestDTO(patientId, "USD"), BillingAccountResponseDTO.class);

        ResponseEntity<String> dup =
                rest.postForEntity(BASE, new OpenAccountRequestDTO(patientId, "EUR"), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("invalid currency is rejected with 400")
    void invalidCurrencyRejected() {
        ResponseEntity<String> bad = rest.postForEntity(
                BASE, new OpenAccountRequestDTO(UUID.randomUUID(), "dollars"), String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("unknown id returns 404")
    void unknownIdReturns404() {
        assertThat(rest.getForEntity(BASE + "/{id}", String.class, UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
