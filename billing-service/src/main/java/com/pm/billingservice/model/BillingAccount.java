package com.pm.billingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
}
