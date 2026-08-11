package com.pm.billingservice.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pm.billingservice.exception.AccountHasBalanceException;
import com.pm.billingservice.exception.AccountNotActiveException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Domain-invariant tests for the account lifecycle: the money-movement guard and deactivation rule. */
@DisplayName("BillingAccount (domain)")
class BillingAccountTest {

    private BillingAccount account() {
        BillingAccount a = BillingAccount.openFor(UUID.randomUUID(), "USD"); // ACTIVE, balance 0
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        return a;
    }

    @Test
    @DisplayName("deactivate closes an empty account")
    void deactivateEmptyCloses() {
        BillingAccount a = account();
        a.deactivate();
        assertThat(a.getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("deactivate rejects a funded account (the compensation trigger) and leaves it ACTIVE")
    void deactivateFundedRejects() {
        BillingAccount a = account();
        a.credit(new BigDecimal("10.00"));

        assertThatThrownBy(a::deactivate).isInstanceOf(AccountHasBalanceException.class);
        assertThat(a.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("deactivate is idempotent once closed")
    void deactivateIdempotent() {
        BillingAccount a = account();
        a.deactivate();
        a.deactivate(); // no throw
        assertThat(a.getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("credit and debit are rejected on a non-active (closed) account")
    void moneyMovementRequiresActive() {
        BillingAccount a = account();
        a.deactivate(); // CLOSED

        assertThatThrownBy(() -> a.credit(new BigDecimal("5.00"))).isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> a.debit(new BigDecimal("5.00"))).isInstanceOf(AccountNotActiveException.class);
    }
}
