-- The "who" behind each change, stamped by JPA auditing from the authenticated principal
-- (@CreatedBy/@LastModifiedBy). Nullable: rows created before auth existed, and background/system
-- writes, may have no user. Length matches the username column in auth-service.
ALTER TABLE patients ADD COLUMN created_by VARCHAR(100) NULL;
ALTER TABLE patients ADD COLUMN updated_by VARCHAR(100) NULL;
