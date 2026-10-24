-- OTA v2: signed release manifest + partner protocol gate (docs/ota-design.md §2, §4).
-- Expand-only: nullable columns or columns with defaults; the previous backend image ignores them.
ALTER TABLE app_releases ADD COLUMN manifest_b64 TEXT NULL;
ALTER TABLE app_releases ADD COLUMN manifest_sig VARCHAR(128) NULL;
ALTER TABLE app_releases ADD COLUMN protocol_version INT NOT NULL DEFAULT 1;
ALTER TABLE app_releases ADD COLUMN min_peer_protocol INT NOT NULL DEFAULT 1;
ALTER TABLE app_releases ADD COLUMN min_sdk INT NOT NULL DEFAULT 0;
