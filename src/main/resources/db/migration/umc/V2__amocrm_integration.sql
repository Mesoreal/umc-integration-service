-- V1 (and the original plan's data model) never stored file_id on document_review —
-- fine while the file was only needed once, during the synchronous precheck. Re-fetching
-- it later for the amoCRM file link needs it persisted.
ALTER TABLE umc.document_review
    ADD COLUMN file_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE umc.document_review
    ALTER COLUMN file_id DROP DEFAULT;

-- Stable per-review token embedded in the file link shown inside the amoCRM card —
-- never a raw S3 URL, so the link never expires and never needs re-issuing.
ALTER TABLE umc.document_review
    ADD COLUMN file_access_token UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE umc.document_review
    ALTER COLUMN file_access_token DROP DEFAULT;

CREATE UNIQUE INDEX idx_document_review_file_access_token ON umc.document_review(file_access_token);

-- Single-row table holding the live amoCRM OAuth2 tokens. amoCRM rotates the refresh
-- token on every use, so the *current* one must be persisted, not just re-read from
-- config — AMOCRM_REFRESH_TOKEN only seeds this table the first time it's empty.
CREATE TABLE umc.amocrm_token (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    access_token TEXT,
    refresh_token TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
