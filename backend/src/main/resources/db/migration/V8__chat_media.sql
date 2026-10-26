-- Chat images + stickers (contract "Chat": type image | sticker). Expand-only: nullable columns.
ALTER TABLE chat_messages ADD COLUMN media_key VARCHAR(26) NULL;
ALTER TABLE chat_messages ADD COLUMN media_width INT NULL;
ALTER TABLE chat_messages ADD COLUMN media_height INT NULL;
