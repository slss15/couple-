-- WxPusher 消息提醒（contract "Chat → WxPusher reminders"）：每个用户自己的 WxPusher UID 和是否显示正文预览。Expand-only。
ALTER TABLE users ADD COLUMN wxpusher_uid VARCHAR(64) NULL;
ALTER TABLE users ADD COLUMN notify_preview BOOLEAN NOT NULL DEFAULT FALSE;
