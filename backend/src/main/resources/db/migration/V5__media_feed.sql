-- Media storage + feed (contract "Media storage", "Feed"): uploads, posts, likes, comments.
-- Expand-only: new tables only. Deletion is physical (post delete cascades in code, not FKs — V1 uses none).

CREATE TABLE media_objects (
    storage_key   VARCHAR(26)   NOT NULL,
    couple_id     VARCHAR(26)   NOT NULL,
    purpose       VARCHAR(16)   NOT NULL DEFAULT 'post',  -- post | avatar
    mime_type     VARCHAR(64)   NOT NULL,
    size          BIGINT        NOT NULL,
    width         INT           NOT NULL,
    height        INT           NOT NULL,
    thumb_key     VARCHAR(26)   NULL,
    used_by_post  VARCHAR(26)   NULL,
    created_at    BIGINT        NOT NULL,
    PRIMARY KEY (storage_key)
);
CREATE INDEX idx_media_couple ON media_objects (couple_id, created_at);
CREATE INDEX idx_media_orphan ON media_objects (purpose, created_at);

CREATE TABLE posts (
    id          VARCHAR(26)   NOT NULL,
    couple_id   VARCHAR(26)   NOT NULL,
    author_id   VARCHAR(26)   NOT NULL,
    text        VARCHAR(4000) NOT NULL,
    created_at  BIGINT        NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_posts_couple ON posts (couple_id, created_at, id);

CREATE TABLE post_likes (
    post_id    VARCHAR(26) NOT NULL,
    user_id    VARCHAR(26) NOT NULL,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (post_id, user_id)
);

CREATE TABLE post_comments (
    id         VARCHAR(26)   NOT NULL,
    post_id    VARCHAR(26)   NOT NULL,
    author_id  VARCHAR(26)   NOT NULL,
    text       VARCHAR(1000) NOT NULL,
    created_at BIGINT        NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_comments_post ON post_comments (post_id, created_at, id);
