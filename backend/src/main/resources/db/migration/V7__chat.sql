-- Chat P1a (contract "Chat"): text messages + per-user read cursor. Expand-only.

CREATE TABLE chat_messages (
    id          VARCHAR(26)   NOT NULL,
    couple_id   VARCHAR(26)   NOT NULL,
    sender_id   VARCHAR(26)   NOT NULL,
    msg_type    VARCHAR(16)   NOT NULL DEFAULT 'text',
    text        VARCHAR(4000) NOT NULL,
    reply_to    VARCHAR(26)   NULL,
    client_ts   BIGINT        NOT NULL,
    created_at  BIGINT        NOT NULL,
    recalled_at BIGINT        NULL,
    PRIMARY KEY (id)
);

-- Ascending catch-up (created_at, id) is the conversation order; the same index serves `before` pages.
CREATE INDEX idx_chat_messages_couple ON chat_messages (couple_id, created_at, id);

CREATE TABLE chat_reads (
    couple_id  VARCHAR(26) NOT NULL,
    user_id    VARCHAR(26) NOT NULL,
    up_to_id   VARCHAR(26) NOT NULL,
    updated_at BIGINT      NOT NULL,
    PRIMARY KEY (couple_id, user_id)
);
