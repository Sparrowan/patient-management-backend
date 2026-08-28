package com.pm.analyticsservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Read model: one row per <b>currently-active</b> patient. The live active count is
 * {@code COUNT(*)} over this table. Membership is the whole state — a bare id, no attributes — so
 * projecting an event is a convergent set operation (add the id on registration, remove it on
 * deletion), which makes the projection idempotent and replay-safe without a dedup ledger.
 */
@Entity
@Table(name = "active_patients")
@Getter
public class ActivePatient {

    @Id
    @Column(name = "patient_id", length = 36)
    private String patientId;

    protected ActivePatient() {
    }

    private ActivePatient(String patientId) {
        this.patientId = patientId;
    }

    public static ActivePatient of(String patientId) {
        return new ActivePatient(patientId);
    }
}
