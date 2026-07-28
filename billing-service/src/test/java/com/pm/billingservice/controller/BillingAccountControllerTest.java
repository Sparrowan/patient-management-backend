package com.pm.billingservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.dto.PagedResponse;
import com.pm.billingservice.exception.AccountAlreadyExistsException;
import com.pm.billingservice.exception.BillingAccountNotFoundException;
import com.pm.billingservice.model.AccountStatus;
import com.pm.billingservice.service.BillingAccountService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer tests: controller + validation + global exception handler only, service mocked. */
@WebMvcTest(BillingAccountController.class)
@DisplayName("BillingAccountController")
class BillingAccountControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PATIENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private BillingAccountService accountService;

    private BillingAccountResponseDTO response() {
        return new BillingAccountResponseDTO(
                ACCOUNT_ID, PATIENT_ID, AccountStatus.ACTIVE, new BigDecimal("0.00"), "USD", 0L);
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
}
