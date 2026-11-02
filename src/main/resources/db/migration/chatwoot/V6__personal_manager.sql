-- Personal managers (docs/backend/artifacts/personal-manager/plan.md): what integration-service last saw of each row of
-- Directus «client_managers» and the journal of who assigned whom.

-- One per Directus row. user_id is null while the phone has no account; agent_id null — no manager in the row.
-- notified: the app's chat header and the Chatwoot contact attribute show this assignment (false — to be sent).
CREATE TABLE chatwoot.personal_manager (
    directus_id UUID PRIMARY KEY,
    user_id UUID UNIQUE,
    agent_id BIGINT,
    agent_name TEXT,
    assigned_by TEXT,
    assigned_at TIMESTAMP WITH TIME ZONE,
    notified BOOLEAN NOT NULL DEFAULT FALSE
);

-- Kept after the account is deleted: no phone, only user_id.
CREATE TABLE chatwoot.personal_manager_history (
    id BIGSERIAL PRIMARY KEY,
    directus_id UUID NOT NULL,
    user_id UUID,
    agent_id BIGINT,
    agent_name TEXT,
    assigned_by TEXT,
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_personal_manager_history_user ON chatwoot.personal_manager_history (user_id);
CREATE INDEX idx_personal_manager_history_row ON chatwoot.personal_manager_history (directus_id);

-- The chat header shows the agent's name and photo also when nobody is assigned (the personal manager).
ALTER TABLE chatwoot.chat_agent ADD COLUMN name TEXT;
ALTER TABLE chatwoot.chat_agent ADD COLUMN avatar_url TEXT;
