-- 定时消息（contract "Chat → Scheduled messages"）：规则存服务器，到点由服务端代发，手机关机也能送达。Expand-only.
-- rule_kind: ONCE(at_ms) | DAILY(fire_time) | WEEKLY(fire_dow, fire_time) | YEARLY(fire_month, fire_day, fire_time)
-- next_fire_at NULL = 已结束（ONCE 已发送）。
CREATE TABLE scheduled_messages (
    id            VARCHAR(26)   NOT NULL,
    couple_id     VARCHAR(26)   NOT NULL,
    sender_id     VARCHAR(26)   NOT NULL,
    msg_type      VARCHAR(16)   NOT NULL DEFAULT 'text',
    text          VARCHAR(4000) NOT NULL DEFAULT '',
    media_key     VARCHAR(26)   NULL,
    media_width   INT           NULL,
    media_height  INT           NULL,
    rule_kind     VARCHAR(8)    NOT NULL,
    at_ms         BIGINT        NULL,
    fire_time     VARCHAR(5)    NULL,
    fire_dow      INT           NULL,
    fire_month    INT           NULL,
    fire_day      INT           NULL,
    tz            VARCHAR(64)   NOT NULL DEFAULT 'Asia/Shanghai',
    next_fire_at  BIGINT        NULL,
    paused        BOOLEAN       NOT NULL DEFAULT FALSE,
    last_fired_at BIGINT        NULL,
    created_at    BIGINT        NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_scheduled_due ON scheduled_messages (paused, next_fire_at);
CREATE INDEX idx_scheduled_sender ON scheduled_messages (sender_id, created_at);
CREATE INDEX idx_scheduled_couple ON scheduled_messages (couple_id);
