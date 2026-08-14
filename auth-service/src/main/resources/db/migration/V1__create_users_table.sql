-- Application users. Plural table name sidesteps the `user` reserved word; native UUID + InnoDB
-- match the other services. The password is stored ONLY as a BCrypt hash (never plaintext).
-- Roles are a space-separated authority list (e.g. 'ROLE_ADMIN ROLE_USER') — a normalized
-- user_roles table is a later refinement.
CREATE TABLE users (
    id            UUID          NOT NULL,
    username      VARCHAR(100)  NOT NULL,
    password_hash VARCHAR(100)  NOT NULL,
    roles         VARCHAR(255)  NOT NULL,
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    version       BIGINT        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username)
) ENGINE=InnoDB;
