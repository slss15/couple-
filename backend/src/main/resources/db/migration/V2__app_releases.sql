-- P0.5 auto-update: APKs mirrored from GitHub Releases.
-- Expand-only migration (contract "Auto-update"): adds a table, never drops/renames existing columns,
-- so the previous backend image keeps working against this schema during a rolling update / rollback.
-- Release settings live in server_settings under key 'release.settings' (JSON), updater status under 'updater.status'.

CREATE TABLE app_releases (
    id                         VARCHAR(26)  NOT NULL,
    version_code               INT          NOT NULL,
    version_name               VARCHAR(32)  NOT NULL,
    sha256                     VARCHAR(64)  NOT NULL,
    size                       BIGINT       NOT NULL,
    signing_cert_sha256        VARCHAR(64)  NOT NULL,
    min_supported_version_code INT          NOT NULL DEFAULT 0,
    notes                      TEXT         NULL,
    published_at               BIGINT       NOT NULL,
    file_path                  VARCHAR(512) NOT NULL,
    status                     VARCHAR(16)  NOT NULL,
    rollout_percent            INT          NOT NULL DEFAULT 100,
    created_at                 BIGINT       NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_app_releases_version_code UNIQUE (version_code),
    CONSTRAINT ck_app_releases_status CHECK (status IN ('MIRRORED', 'PUBLISHED', 'WITHDRAWN')),
    CONSTRAINT ck_app_releases_rollout CHECK (rollout_percent BETWEEN 0 AND 100)
);
CREATE INDEX idx_app_releases_status ON app_releases (status, version_code);
