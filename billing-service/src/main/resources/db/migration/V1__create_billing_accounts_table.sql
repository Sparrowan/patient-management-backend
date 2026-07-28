-- Initial schema: billing_accounts. Money (balance) is DECIMAL(19,2) — never a float. Native
-- UUID ids (MariaDB 10.7+), InnoDB for ACID. One account per patient (unique patient_id).
CREATE TABLE billing_accounts (
    id          UUID          NOT NULL,
    patient_id  UUID          NOT NULL,
    status      VARCHAR(20)   NOT NULL,
    balance     DECIMAL(19,2) NOT NULL,
    currency    VARCHAR(3)    NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    version     BIGINT        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_billing_accounts_patient UNIQUE (patient_id)
) ENGINE=InnoDB;
