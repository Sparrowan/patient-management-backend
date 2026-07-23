-- Initial schema: patients. Matches the Patient entity. Uses MariaDB's native UUID type
-- (10.7+), which is what Hibernate's MariaDBDialect expects for a java.util.UUID id.
-- InnoDB for ACID + the unique-email constraint.
CREATE TABLE patients (
    id              UUID         NOT NULL,
    name            VARCHAR(100) NOT NULL,
    email           VARCHAR(255) NOT NULL,
    address         VARCHAR(255) NOT NULL,
    date_of_birth   DATE         NOT NULL,
    registered_date DATE         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_patients_email UNIQUE (email)
) ENGINE=InnoDB;
