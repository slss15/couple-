-- TA page (contract "TA"): user profile extensions + shared important days. Expand-only.
ALTER TABLE users ADD COLUMN bio VARCHAR(200) NULL;
ALTER TABLE users ADD COLUMN birthday VARCHAR(5) NULL;      -- "MM-DD"
ALTER TABLE users ADD COLUMN birth_year SMALLINT NULL;      -- null = 不展示年龄
ALTER TABLE users ADD COLUMN show_birthday BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE important_days (
    id         VARCHAR(26)  NOT NULL,
    couple_id  VARCHAR(26)  NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    date_month SMALLINT     NOT NULL,
    date_day   SMALLINT     NOT NULL,
    date_year  SMALLINT     NULL,
    created_by VARCHAR(26)  NOT NULL,
    created_at BIGINT       NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_important_days_couple ON important_days (couple_id, date_month, date_day);
