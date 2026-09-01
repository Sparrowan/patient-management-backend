package com.pm.billingservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pm.billingservice.config.SecurityConfig;
import com.pm.billingservice.dto.TransferResponseDTO;
import com.pm.billingservice.model.TransferStatus;
import com.pm.billingservice.repository.IdempotencyRecordRepository;
import com.pm.billingservice.service.TransferService;
import com.pm.billingservice.support.MetricsTestConfig;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer tests: controller + validation + security only, service mocked. */
@WebMvcTest(TransferController.class)
@Import({SecurityConfig.class, MetricsTestConfig.class})
@WithMockUser(roles = "ADMIN") // transfers are money movement → admin-only; a USER-403 case is below
@DisplayName("TransferController")
class TransferControllerTest {

    private static final UUID FROM = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TransferService transferService;
    @MockitoBean private JwtDecoder jwtDecoder; // satisfies the resource-server chain; unused with @WithMockUser
    // The @Idempotent interceptor is loaded by @WebMvcTest via its WebMvcConfigurer; mock its store
    // (returns Optional.empty() → the interceptor claims and proceeds, inert for these tests).
    @MockitoBean private IdempotencyRecordRepository idempotencyRecordRepository;

    private String body(UUID from, UUID to, String amount) {
        return """
                {"fromAccountId":"%s","toAccountId":"%s","amount":%s}""".formatted(from, to, amount);
    }

    private TransferResponseDTO response() {
        return new TransferResponseDTO(
                UUID.fromString("33333333-3333-3333-3333-333333333333"), FROM, TO,
                new BigDecimal("30.00"), "USD", TransferStatus.COMPLETED, "test", "k1", null);
    }

    @Test
    @DisplayName("POST returns 201 for a valid transfer")
    void transferReturns201() throws Exception {
        when(transferService.transfer(any(), eq("k1"))).thenReturn(response());

        mockMvc.perform(post("/api/v1/billing-accounts/transfers")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, TO, "30.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(30.00))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST to the same account returns 400 (validation), service untouched")
    void sameAccountReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/billing-accounts/transfers")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, FROM, "30.00")))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("POST without an Idempotency-Key returns 400")
    void missingKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/billing-accounts/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, TO, "30.00")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST as a non-admin (USER) returns 403")
    @WithMockUser(roles = "USER")
    void transferAsUserReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/billing-accounts/transfers")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, TO, "30.00")))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(transferService);
    }
}
