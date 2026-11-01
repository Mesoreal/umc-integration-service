-- Transfers of app requests between inboxes (docs/backend/artifacts/messenger/plan-chat-departments.md).

-- The Chatwoot id chat-service knows a message by. A transfer copies messages into a new conversation (new Chatwoot
-- ids); the link is re-pointed to the copy, external_cw_id stays what chat-service has.
ALTER TABLE chatwoot.chat_message_link ADD COLUMN external_cw_id BIGINT;
UPDATE chatwoot.chat_message_link SET external_cw_id = cw_message_id;
ALTER TABLE chatwoot.chat_message_link ALTER COLUMN external_cw_id SET NOT NULL;
CREATE UNIQUE INDEX uq_chat_message_link_external ON chatwoot.chat_message_link (external_cw_id);

-- The conversation a request was moved to; set when the move starts (the request is «transferring» until it is done).
ALTER TABLE chatwoot.chat_conversation ADD COLUMN transferred_to BIGINT;

-- Progress of a transfer: which messages are already copied, so a repeated transfer copies only the rest.
CREATE TABLE chatwoot.chat_transfer_copy (
    old_cw_message_id BIGINT PRIMARY KEY,
    new_cw_message_id BIGINT NOT NULL,
    new_conversation_id BIGINT NOT NULL
);
