package ru.provless.integration.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A message a transfer already copied: old Chatwoot id → its copy. */
@Entity
@Table(schema = "chatwoot", name = "chat_transfer_copy")
@Getter
@NoArgsConstructor
public class ChatTransferCopy {

    @Id
    @Column(name = "old_cw_message_id")
    private long oldCwMessageId;

    @Column(name = "new_cw_message_id", nullable = false)
    private long newCwMessageId;

    @Column(name = "new_conversation_id", nullable = false)
    private long newConversationId;

    public ChatTransferCopy(long oldCwMessageId, long newCwMessageId, long newConversationId) {
        this.oldCwMessageId = oldCwMessageId;
        this.newCwMessageId = newCwMessageId;
        this.newConversationId = newConversationId;
    }
}
