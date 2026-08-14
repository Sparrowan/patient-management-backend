-- Add email so users can log in with username OR email. Added as a *new* migration (never edit an
-- applied one — that breaks Flyway's checksum). Backfill existing rows before making it NOT NULL.
ALTER TABLE users ADD COLUMN email VARCHAR(255) NULL AFTER username;
UPDATE users SET email = CONCAT(username, '@auth.local') WHERE email IS NULL;
ALTER TABLE users MODIFY COLUMN email VARCHAR(255) NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);
