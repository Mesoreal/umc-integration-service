-- App user ↔ Chatwoot contact. The app is one more channel (inbox «Приложение», source_id = user_id)
-- of the contact that messenger bridges already keep per phone number; see
-- docs/backend/artifacts/shop/chatwoot/plan-app-contact-merge.md.
CREATE SCHEMA IF NOT EXISTS chatwoot;

CREATE TABLE chatwoot.app_contact_link (
    user_id UUID PRIMARY KEY,
    contact_id BIGINT NOT NULL,
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_app_contact_link_contact_id ON chatwoot.app_contact_link(contact_id);
