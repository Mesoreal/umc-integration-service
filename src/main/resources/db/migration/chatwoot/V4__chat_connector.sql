-- App chat connector (docs/backend/artifacts/messenger/plan-chat-connector.md, §4): chat-service ↔ Chatwoot.

-- Chatwoot conversations of app users. One is current (where the user's messages go); a resolved current one is
-- replaced by a new conversation on the user's next message (a new request).
CREATE TABLE chatwoot.chat_conversation (
    conversation_id    BIGINT PRIMARY KEY,                 -- Chatwoot display_id, the id of its API paths
    user_id            UUID        NOT NULL,
    contact_id         BIGINT      NOT NULL,
    inbox_id           BIGINT      NOT NULL,
    status             TEXT        NOT NULL,
    current            BOOLEAN     NOT NULL DEFAULT TRUE,
    assignee_id        BIGINT,
    agent_last_seen_at TIMESTAMP WITH TIME ZONE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_chat_conversation_current ON chatwoot.chat_conversation (user_id) WHERE current;
CREATE INDEX idx_chat_conversation_user ON chatwoot.chat_conversation (user_id);

-- Every chat message that exists on both sides. Client messages: message_id = chat-service id, read_by_manager drives
-- the read poll. Manager messages: delivered_to_client drives the delivered status sent back to Chatwoot.
CREATE TABLE chatwoot.chat_message_link (
    cw_message_id       BIGINT PRIMARY KEY,
    message_id          UUID UNIQUE,
    user_id             UUID        NOT NULL,
    conversation_id     BIGINT      NOT NULL,
    direction           TEXT        NOT NULL CHECK (direction IN ('client', 'manager')),
    cw_created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    read_by_manager     BOOLEAN     NOT NULL DEFAULT FALSE,
    delivered_to_client BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_chat_message_link_unread ON chatwoot.chat_message_link (conversation_id)
    WHERE direction = 'client' AND NOT read_by_manager;
CREATE INDEX idx_chat_message_link_undelivered ON chatwoot.chat_message_link (user_id, cw_message_id)
    WHERE direction = 'manager' AND NOT delivered_to_client;

-- Last seen presence of agents, to report only changes.
CREATE TABLE chatwoot.chat_agent (
    agent_id     BIGINT PRIMARY KEY,
    availability TEXT NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE
);

-- Last reported working hours of an inbox (a hash of its schedule), to report only changes.
CREATE TABLE chatwoot.chat_inbox_hours (
    inbox_id    BIGINT PRIMARY KEY,
    hours_hash  TEXT NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
