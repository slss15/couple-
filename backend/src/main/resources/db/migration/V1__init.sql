-- Couple backend P0 schema.
-- Written to run on MySQL 8 (production) and on H2 in MODE=MySQL (tests).
-- Charset/collation come from the database default (docker-compose starts MySQL with utf8mb4).
-- All times are epoch milliseconds (BIGINT); all ids are ULID strings (26 chars).

CREATE TABLE users (
    id            VARCHAR(26)  NOT NULL,
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    avatar_url    VARCHAR(512) NULL,
    role          VARCHAR(16)  NOT NULL,
    couple_id     VARCHAR(26)  NULL,
    created_at    BIGINT       NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username)
);

CREATE TABLE refresh_tokens (
    id          VARCHAR(26)  NOT NULL,
    user_id     VARCHAR(26)  NOT NULL,
    family_id   VARCHAR(26)  NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    device_id   VARCHAR(128) NOT NULL,
    device_name VARCHAR(128) NOT NULL,
    created_at  BIGINT       NOT NULL,
    expires_at  BIGINT       NOT NULL,
    used_at     BIGINT       NULL,
    revoked_at  BIGINT       NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user_device ON refresh_tokens (user_id, device_id);

CREATE TABLE couples (
    id          VARCHAR(26) NOT NULL,
    user_a      VARCHAR(26) NOT NULL,
    user_b      VARCHAR(26) NOT NULL,
    paired_at   BIGINT      NOT NULL,
    anniversary BIGINT      NULL,
    deleted_at  BIGINT      NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_couples_user_a FOREIGN KEY (user_a) REFERENCES users (id),
    CONSTRAINT fk_couples_user_b FOREIGN KEY (user_b) REFERENCES users (id)
);

-- One active pairing code per user; issuing a new code replaces the row.
CREATE TABLE pair_codes (
    user_id    VARCHAR(26) NOT NULL,
    code       VARCHAR(6)  NOT NULL,
    created_at BIGINT      NOT NULL,
    expires_at BIGINT      NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_pair_codes_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_pair_codes_code ON pair_codes (code);

CREATE TABLE unpair_requests (
    id           VARCHAR(26) NOT NULL,
    couple_id    VARCHAR(26) NOT NULL,
    requested_by VARCHAR(26) NOT NULL,
    created_at   BIGINT      NOT NULL,
    expires_at   BIGINT      NOT NULL,
    confirmed_by VARCHAR(26) NULL,
    confirmed_at BIGINT      NULL,
    cancelled_at BIGINT      NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_unpair_requests_couple FOREIGN KEY (couple_id) REFERENCES couples (id)
);
CREATE INDEX idx_unpair_requests_couple ON unpair_requests (couple_id);

-- Server-side key/value settings: admin config JSON, config version, generated bootstrap key seed.
CREATE TABLE server_settings (
    setting_key   VARCHAR(64) NOT NULL,
    setting_value TEXT        NOT NULL,
    updated_at    BIGINT      NOT NULL,
    PRIMARY KEY (setting_key)
);

CREATE TABLE audit_log (
    id       VARCHAR(26)  NOT NULL,
    actor_id VARCHAR(26)  NULL,
    action   VARCHAR(64)  NOT NULL,
    target   VARCHAR(128) NULL,
    meta     TEXT         NULL,
    ts       BIGINT       NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_audit_log_ts ON audit_log (ts);
