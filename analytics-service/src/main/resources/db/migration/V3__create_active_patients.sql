-- Second read model: the SET of currently-active patients (live gauge = COUNT(*)).
--
-- Contrast with daily_registrations on purpose. That one is a *counter* (increment per event), which
-- is not naturally idempotent — so it needs the processed_events ledger to avoid double-counting a
-- redelivery. This one models *state*: a row per active patient, keyed by patient_id. Applying an
-- event is then a convergent, idempotent operation — register = INSERT the id (already there → no-op),
-- delete = remove the id (already gone → no-op). Because patient-events is keyed by patient_id, a
-- patient's register always precedes its delete within the partition, so ordering holds. This shape
-- is naturally replay-safe: re-applying the whole log lands on the same final set.
CREATE TABLE active_patients (
    patient_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (patient_id)
) ENGINE = InnoDB;
