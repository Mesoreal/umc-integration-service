-- Front requests S5 §10 (decided 18.11.2026): documents of a person are checked in amoCRM (the training centre), everything else
-- in the Chatwoot inbox «Проверка документов» (sales). The decision goes back to profile-service.

-- Where the document goes: PHYSICAL → amoCRM, LEGAL → Chatwoot (null — sent before §10: amoCRM, as then).
ALTER TABLE umc.document_review ADD COLUMN profile_type VARCHAR(20);

-- The decision taken from the amoCRM deal, kept until profile-service has it (resent until decision_sent_at is set).
ALTER TABLE umc.document_review ADD COLUMN decision_reason_code VARCHAR(50);
ALTER TABLE umc.document_review ADD COLUMN decision_reason_text VARCHAR(500);
ALTER TABLE umc.document_review ADD COLUMN decision_comment VARCHAR(1000);
ALTER TABLE umc.document_review ADD COLUMN decision_summary VARCHAR(500);
ALTER TABLE umc.document_review ADD COLUMN decision_sent_at TIMESTAMP WITH TIME ZONE;

-- A request in the Chatwoot inbox «Проверка документов»: a document of an organization's profile or a LEGAL / IE payer. QUEUED —
-- not in Chatwoot yet (Chatwoot off or failing — retried), OPEN — the conversation is there, DECIDED, CANCELLED (the client
-- deleted it before the decision).
CREATE TABLE umc.review_request (
    id                 UUID PRIMARY KEY,
    kind               VARCHAR(20)   NOT NULL,
    subject_id         UUID          NOT NULL,
    user_id            UUID          NOT NULL,
    document_review_id UUID REFERENCES umc.document_review (id) ON DELETE SET NULL,
    status             VARCHAR(20)   NOT NULL,
    conversation_id    BIGINT,
    attempts           INT           NOT NULL DEFAULT 0,
    last_error         VARCHAR(1000),
    decision           VARCHAR(20),
    decided_by         VARCHAR(255),
    decided_at         TIMESTAMP WITH TIME ZONE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (kind, subject_id)
);

CREATE INDEX idx_review_request_status ON umc.review_request (status);
CREATE INDEX idx_review_request_conversation ON umc.review_request (conversation_id);
