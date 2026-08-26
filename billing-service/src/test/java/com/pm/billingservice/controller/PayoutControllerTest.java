package com.pm.billingservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pm.billingservice.config.SecurityConfig;
import com.pm.billingservice.dto.PayoutResponseDTO;
import com.pm.billingservice.model.PayoutStatus;
import com.pm.billingservice.service.PayoutService;
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
@WebMvcTest(PayoutController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN") // payouts are money movement → admin-only; a USER-403 case is below
@DisplayName("PayoutController")
class PayoutControllerTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DEST = "DE89370400440532013000";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PayoutService payoutService;
    @MockitoBean private JwtDecoder jwtDecoder; // satisfies the resource-server chain; unused with @WithMockUser

    private String body(String amount) {
        return """
                {"sourceAccountId":"%s","destinationReference":"%s","amount":%s}"""
                .formatted(ACCOUNT, DEST, amount);
    }

    private PayoutResponseDTO response() {
        return new PayoutResponseDTO(
                UUID.fromString("33333333-3333-3333-3333-333333333333"), ACCOUNT, DEST,
                new BigDecimal("30.00"), "USD", PayoutStatus.PENDING, "rent", "k1", null);
    }

    @Test
    @DisplayName("POST returns 202 Accepted with PENDING for a valid payout")
    void payoutReturns202() throws Exception {
        when(payoutService.initiate(any(), eq("k1"))).thenReturn(response());

        mockMvc.perform(post("/api/v1/billing-accounts/payouts")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("30.00")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST without an Idempotency-Key returns 400")
    void missingKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/billing-accounts/payouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("30.00")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST as a non-admin (USER) returns 403")
    @WithMockUser(roles = "USER")
    void payoutAsUserReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/billing-accounts/payouts")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("30.00")))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(payoutService);
    }
}
