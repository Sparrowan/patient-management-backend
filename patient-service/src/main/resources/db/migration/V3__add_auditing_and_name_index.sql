-- Audit timestamps (populated by Spring Data JPA auditing on the BaseEntity). DEFAULT covers
-- rows that predate this migration; Hibernate supplies the value on new inserts/updates.
ALTER TABLE patients
    ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

-- The list endpoint defaults to sorting by name.
CREATE INDEX idx_patients_name ON patients (name);
