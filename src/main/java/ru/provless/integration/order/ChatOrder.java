package ru.provless.integration.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** An order that reached the managers: its request was moved to sales, assigned and noted. */
@Entity
@Table(schema = "chatwoot", name = "chat_order")
@Getter
@NoArgsConstructor
public class ChatOrder {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private long number;

    @Column(name = "conversation_id", nullable = false)
    private long conversationId;

    public ChatOrder(UUID orderId, UUID userId, long number, long conversationId) {
        this.orderId = orderId;
        this.userId = userId;
        this.number = number;
        this.conversationId = conversationId;
    }
}
