package com.pm.billingservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pm.billingservice.config.SecurityConfig;
import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.LedgerEntryResponseDTO;
import com.pm.billingservice.dto.PagedResponse;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.exception.InsufficientFundsException;
import com.pm.billingservice.model.AccountStatus;
import com.pm.billingservice.model.EntryType;
import com.pm.billingservice.repository.IdempotencyRecordRepository;
import com.pm.billingservice.service.BillingAccountService;
import com.pm.billingservice.support.MetricsTestConfig;
import java.math.BigDecimal;
import java.util.List;
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

/** Web-layer tests: controller + validation + global exception handler only, service mocked. */
@WebMvcTest(BillingAccountController.class)
@Import({SecurityConfig.class, MetricsTestConfig.class})
@WithMockUser(roles = "ADMIN") // most billing ops (incl. money movement) need admin; a USER-403 case is below
@DisplayName("BillingAccountController")
class BillingAccountControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PATIENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private BillingAccountService accountService;
    @MockitoBean private JwtDecoder jwtDecoder; // satisfies the resource-server chain; unused with @WithMockUser
    // The @Idempotent interceptor is loaded by @WebMvcTest via its WebMvcConfigurer; mock its store.
    @MockitoBean private IdempotencyRecordRepository idempotencyRecordRepository;

    private BillingAccountResponseDTO response() {
        return new BillingAccountResponseDTO(
                ACCOUNT_ID, PATIENT_ID, AccountStatus.ACTIVE, new BigDecimal("0.00"), "USD", 0L);
    }

    private LedgerEntryResponseDTO ledgerResponse() {
        return new LedgerEntryResponseDTO(
                UUID.fromString("33333333-3333-3333-3333-333333333333"), ACCOUNT_ID, EntryType.CREDIT,
                new BigDecimal("50.00"), new BigDecimal("50.00"), null, "k1", null);
    }

    @Test
    @DisplayName("GET list returns 200 with a paginated envelope")
    void listReturnsPage() throws Exception {
        when(accountService.getAccounts(any()))
                .thenReturn(new PagedResponse<>(List.of(response()), 0, 20, 1, 1, true));

        mockMvc.perform(get("/api/v1/billing-accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].currency").value("USD"))
                .andExpect(jsonPath("$.content[0].balance").value(0.00))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /{id} returns 200 when found")
    void getReturnsAccount() throws Exception {
        when(accountService.getAccount(ACCOUNT_ID)).thenReturn(response());

        mockMvc.perform(get("/api/v1/billing-accounts/{id}", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.patientId").value(PATIENT_ID.toString()));
    }

    @Test
    @DisplayName("GET /{id} returns 404 ProblemDetail when absent")
    void getReturns404() throws Exception {
        when(accountService.getAccount(ACCOUNT_ID)).thenThrow(new BillingAccountNotFoundException(ACCOUNT_ID));

        mockMvc.perform(get("/api/v1/billing-accounts/{id}", ACCOUNT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Billing account not found"));
    }

    @Test
    @DisplayName("POST returns 201 for a valid request")
    void openReturns201() throws Exception {
        when(accountService.openAccount(any())).thenReturn(response());
        String body = """
                {"patientId":"22222222-2222-2222-2222-222222222222","currency":"USD"}""";

        mockMvc.perform(post("/api/v1/billing-accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()));
    }

    @Test
    @DisplayName("POST returns 400 for an invalid currency / missing patientId")
    void openReturns400() throws Exception {
        String body = """
                {"patientId":null,"currency":"dollars"}""";

        mockMvc.perform(post("/api/v1/billing-accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation error"))
                .andExpect(jsonPath("$.errors.patientId").exists())
                .andExpect(jsonPath("$.errors.currency").exists());
    }

    @Test
    @DisplayName("POST returns 409 when the patient already has an account")
    void openReturns409() throws Exception {
        when(accountService.openAccount(any())).thenThrow(new AccountAlreadyExistsException(PATIENT_ID));
        String body = """
                {"patientId":"22222222-2222-2222-2222-222222222222","currency":"USD"}""";

        mockMvc.perform(post("/api/v1/billing-accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Account already exists"));
    }

    @Test
    @DisplayName("POST /credit with an Idempotency-Key returns 200")
    void creditReturns200() throws Exception {
        when(accountService.credit(eq(ACCOUNT_ID), any(), eq("k1"))).thenReturn(ledgerResponse());

        mockMvc.perform(post("/api/v1/billing-accounts/{id}/credit", ACCOUNT_ID)
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(50.00));
    }

    @Test
    @DisplayName("POST /credit without an Idempotency-Key returns 400")
    void creditMissingKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/billing-accounts/{id}/credit", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /debit with insufficient funds returns 422")
    void debitReturns422() throws Exception {
        when(accountService.debit(eq(ACCOUNT_ID), any(), eq("k1")))
                .thenThrow(new InsufficientFundsException(ACCOUNT_ID, new BigDecimal("0.00"), new BigDecimal("50.00")));

        mockMvc.perform(post("/api/v1/billing-accounts/{id}/debit", ACCOUNT_ID)
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Insufficient funds"));
    }

    @Test
    @DisplayName("POST /credit as a non-admin (USER) returns 403 (money movement is admin-only)")
    @WithMockUser(roles = "USER")
    void creditAsUserReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/billing-accounts/{id}/credit", ACCOUNT_ID)
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(accountService);
    }
}
