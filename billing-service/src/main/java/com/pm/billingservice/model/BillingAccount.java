package com.pm.billingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import com.pm.billingservice.exception.InsufficientFundsException;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;

/**
 * A patient's billing account. Rich domain model: created through the {@link #openFor} factory,
 * state changes via intention-revealing behavior, no public setters. Audit timestamps + version
 * are inherited from {@link BaseEntity}.
 *
 * <p><b>Money is {@link BigDecimal}, never a floating-point type</b> — the balance is stored as
 * {@code DECIMAL(19,2)} and every amount is scaled to the currency's minor unit. One account per
 * patient (unique {@code patient_id}).
 */
@Entity
@Table(name = "billing_accounts")
@Getter
public class BillingAccount extends BaseEntity {

    /** Money is held to 2 decimal places (minor units). */
    private static final int MONEY_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(nullable = false, precision = 19, scale = MONEY_SCALE)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Required by JPA. Use {@link #openFor} to create instances. */
    protected BillingAccount() {
    }

    private BillingAccount(UUID patientId, String currency) {
        this.patientId = patientId;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.balance = BigDecimal.ZERO.setScale(MONEY_SCALE);
    }

    /** Opens a new active account for a patient with a zero balance in the given currency. */
    public static BillingAccount openFor(UUID patientId, String currency) {
        return new BillingAccount(patientId, currency);
    }

    /** Adds funds to the balance. Amount must be positive. */
    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(requirePositive(amount));
    }

    /** Removes funds from the balance. Fails if the balance would go negative. */
    public void debit(BigDecimal amount) {
        requirePositive(amount);
        if (this.balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(this.id, this.balance, amount);
        }
        this.balance = this.balance.subtract(amount);
    }

    /**
     * Deactivates the account when its patient is removed. A funded account is never silently
     * dropped: it is {@link AccountStatus#CLOSED} only when empty, otherwise
     * {@link AccountStatus#SUSPENDED} pending settlement of the remaining balance (a real bank rule
     * — you cannot close an account that still holds money). Idempotent: a no-op once the account is
     * already closed or suspended, so a redelivered {@code PatientDeleted} event changes nothing.
     */
    public void deactivate() {
        if (status == AccountStatus.CLOSED || status == AccountStatus.SUSPENDED) {
            return;
        }
        this.status = balance.signum() == 0 ? AccountStatus.CLOSED : AccountStatus.SUSPENDED;
    }

    private static BigDecimal requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        return amount;
    }
}
