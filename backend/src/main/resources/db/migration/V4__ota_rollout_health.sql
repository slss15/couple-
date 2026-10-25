-- OTA v2: staged rollout, auto-pause, device reports, post-install health (docs/ota-design.md §4, §5).
-- Expand-only: new tables + columns with defaults / nullable.
ALTER TABLE app_releases ADD COLUMN stage VARCHAR(16) NOT NULL DEFAULT 'ALL';
ALTER TABLE app_releases ADD COLUMN paused_at BIGINT NULL;
ALTER TABLE app_releases ADD COLUMN pause_reason VARCHAR(512) NULL;

CREATE TABLE device_reports (
    device_id         VARCHAR(128) NOT NULL,
    user_id           VARCHAR(26)  NOT NULL,
    version_code      INT          NOT NULL,
    protocol_version  INT          NOT NULL DEFAULT 1,
    min_peer_protocol INT          NOT NULL DEFAULT 1,
    sdk_int           INT          NULL,
    reported_at       BIGINT       NOT NULL,
    PRIMARY KEY (device_id)
);
CREATE INDEX idx_device_reports_user ON device_reports (user_id, reported_at);

CREATE TABLE release_health (
    id                VARCHAR(26)  NOT NULL,
    version_code      INT          NOT NULL,
    device_id         VARCHAR(128) NOT NULL,
    user_id           VARCHAR(26)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    from_version_code INT          NULL,
    mqtt_connected    BOOLEAN      NULL,
    detail            VARCHAR(512) NULL,
    created_at        BIGINT       NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_release_health_vc ON release_health (version_code, created_at);
