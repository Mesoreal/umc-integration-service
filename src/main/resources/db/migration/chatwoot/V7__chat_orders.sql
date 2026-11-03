-- Orders that reached the managers through the chat (orders/plan-manager.md §2.1): the request was moved to sales, assigned
-- and got the order's note — once per order.
CREATE TABLE chatwoot.chat_order (
    order_id        UUID PRIMARY KEY,
    user_id         UUID        NOT NULL,
    number          BIGINT      NOT NULL,
    conversation_id BIGINT      NOT NULL,
    placed_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_order_user ON chatwoot.chat_order (user_id);
