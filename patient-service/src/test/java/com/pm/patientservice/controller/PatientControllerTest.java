package com.pm.patientservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.patientservice.dto.PagedResponse;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.config.SecurityConfig;
import com.pm.patientservice.dto.PatientUpdateRequestDTO;
import com.pm.patientservice.exception.EmailAlreadyExistsException;
import com.pm.patientservice.exception.PatientNotFoundException;
import com.pm.patientservice.service.PatientService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer tests: only the controller, its validation, and the global exception handler are
 * loaded ({@code @WebMvcTest}); the service is mocked. Asserts the HTTP contract — status codes,
 * request validation, and the RFC 7807 error shape — without a database.
 */
@WebMvcTest(PatientController.class)
@Import(SecurityConfig.class) // load the real resource-server chain (CSRF off, JWT required)
@WithMockUser // authenticate every request in this slice; the auth rules themselves are tested in the integration test
@DisplayName("PatientController")
class PatientControllerTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate DOB = LocalDate.of(1990, 5, 10);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private PatientService patientService;
    // The resource-server chain needs a JwtDecoder bean; @WithMockUser means it's never actually called.
    @MockitoBean private JwtDecoder jwtDecoder;

    private PatientResponseDTO response(long version) {
        return new PatientResponseDTO(ID, "Ada Lovelace", "ada@example.com", "12 Analytical St", DOB, version);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    @Test
    @DisplayName("GET /api/v1/patients returns 200 with a paginated envelope")
    void listReturnsPage() throws Exception {
        when(patientService.getPatients(any()))
                .thenReturn(new PagedResponse<>(List.of(response(0)), 0, 20, 1, 1, true));

        mockMvc.perform(get("/api/v1/patients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("ada@example.com"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/patients/{id} returns 200 when found")
    void getReturnsPatient() throws Exception {
        when(patientService.getPatient(ID)).thenReturn(response(0));

        mockMvc.perform(get("/api/v1/patients/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()));
    }

    @Test
    @DisplayName("GET /{id} returns an ETag; matching If-None-Match yields 304")
    void getSupportsEtag() throws Exception {
        when(patientService.getPatient(ID)).thenReturn(response(3));

        mockMvc.perform(get("/api/v1/patients/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""));

        mockMvc.perform(get("/api/v1/patients/{id}", ID).header(HttpHeaders.IF_NONE_MATCH, "\"3\""))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("GET /api/v1/patients/{id} returns 404 ProblemDetail when absent")
    void getReturns404() throws Exception {
        when(patientService.getPatient(ID)).thenThrow(new PatientNotFoundException(ID));

        mockMvc.perform(get("/api/v1/patients/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Patient not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("POST /api/v1/patients returns 201 for a valid body")
    void createReturns201() throws Exception {
        when(patientService.createPatient(any())).thenReturn(response(0));
        String body = json(new PatientResponseDTO(null, "Ada Lovelace", "ada@example.com", "12 Analytical St", DOB, 0));

        mockMvc.perform(post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID.toString()));
    }

    @Test
    @DisplayName("POST /api/v1/patients returns 400 with field errors for an invalid body")
    void createReturns400() throws Exception {
        String invalid = """
                {"name":"","email":"not-an-email","address":"x","dateOfBirth":"2999-01-01"}""";

        mockMvc.perform(post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation error"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.dateOfBirth").exists());
    }

    @Test
    @DisplayName("POST /api/v1/patients returns 409 for a duplicate email")
    void createReturns409() throws Exception {
        when(patientService.createPatient(any()))
                .thenThrow(new EmailAlreadyExistsException("ada@example.com"));
        String body = """
                {"name":"Ada","email":"ada@example.com","address":"x","dateOfBirth":"1990-05-10"}""";

        mockMvc.perform(post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Email already in use"));
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} returns 200 for a valid update")
    void updateReturns200() throws Exception {
        when(patientService.updatePatient(eq(ID), any())).thenReturn(response(1));
        String body = json(new PatientUpdateRequestDTO("Ada L.", "ada@example.com", "New Address", DOB, 0L));

        mockMvc.perform(put("/api/v1/patients/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} returns 400 when version is missing")
    void updateReturns400WhenVersionMissing() throws Exception {
        String body = """
                {"name":"Ada","email":"ada@example.com","address":"x","dateOfBirth":"1990-05-10"}""";

        mockMvc.perform(put("/api/v1/patients/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.version").exists());
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} returns 409 on a stale version")
    void updateReturns409OnStaleVersion() throws Exception {
        when(patientService.updatePatient(eq(ID), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("Patient", ID));
        String body = json(new PatientUpdateRequestDTO("Ada", "ada@example.com", "x", DOB, 0L));

        mockMvc.perform(put("/api/v1/patients/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Concurrent modification"));
    }

    @Test
    @DisplayName("DELETE /api/v1/patients/{id} returns 204")
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/patients/{id}", ID)).andExpect(status().isNoContent());
    }
}
