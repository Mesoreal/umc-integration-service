package ru.provless.integration.chat;

/** The user's request is being moved to a department: their next message waits (and is retried) until it is done. */
public class TransferInProgressException extends RuntimeException {

    public TransferInProgressException(long conversationId) {
        super("Conversation " + conversationId + " is being transferred");
    }
}
