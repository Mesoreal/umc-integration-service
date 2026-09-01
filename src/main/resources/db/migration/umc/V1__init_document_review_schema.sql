CREATE SCHEMA IF NOT EXISTS umc;

CREATE TYPE umc.document_type AS ENUM ('PASSPORT', 'DIPLOMA', 'NAME_CHANGE', 'MARRIAGE_CERT');

CREATE TYPE umc.document_review_status AS ENUM (
    'RECEIVED',
    'OCR_PROCESSING',
    'OCR_FAILED',
    'AUTO_REJECTED',
    'PENDING_MANAGER',
    'APPROVED',
    'REJECTED'
);

CREATE TYPE umc.document_review_audit_event_type AS ENUM (
    'OCR_COMPLETED',
    'AUTO_REJECTED',
    'CRM_LEAD_CREATED',
    'CRM_STATUS_CHANGED',
    'STATUS_PUBLISHED'
);

-- No FK to profile-service's DB — document_id is a logical reference only,
-- profile-service and umc-integration-service own separate databases.
CREATE TABLE umc.document_review (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL UNIQUE,
    profile_id UUID NOT NULL,
    user_id UUID NOT NULL,
    document_type umc.document_type NOT NULL,
    status umc.document_review_status NOT NULL DEFAULT 'RECEIVED',
    ocr_raw_text TEXT,
    ocr_confidence NUMERIC(4, 3),
    ocr_matched_keywords JSONB,
    amocrm_lead_id BIGINT,
    amocrm_contact_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_review_profile_id ON umc.document_review(profile_id);

CREATE TABLE umc.document_review_audit (
    id UUID PRIMARY KEY,
    document_review_id UUID NOT NULL REFERENCES umc.document_review(id) ON DELETE CASCADE,
    event_type umc.document_review_audit_event_type NOT NULL,
    payload JSONB,
    actor VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_review_audit_review_id ON umc.document_review_audit(document_review_id);
