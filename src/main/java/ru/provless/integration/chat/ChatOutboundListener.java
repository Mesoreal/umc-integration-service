package ru.provless.integration.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import ru.provless.integration.chatwoot.ChatwootDisabledException;
import ru.provless.integration.chatwoot.ChatwootException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.time.Duration;

/**
 * {@code chat.outbound}: the user's actions → Chatwoot, strictly in order (plan-chat-connector.md §2.1).
 * <ul>
 *   <li>Chatwoot or storage unreachable, 5xx, 429, 401/403 (token or setup) — the same event again after a pause
 *       growing to {@code retry-max}, <b>forever</b>: the queue waits, nothing is lost or reordered;</li>
 *   <li>an event that can't be applied (bad JSON, a 4xx from Chatwoot, a missing file) — logged and set aside in
 *       {@code chat.outbound.dead}, the queue goes on;</li>
 *   <li>anything else — a few tries, then set aside, so a bug can't block the chat forever.</li>
 * </ul>
 */
@Slf4j
@Component
public class ChatOutboundListener {

    static final int UNKNOWN_ERROR_TRIES = 5;

    private final ChatOutboundHandler handler;
    private final ChatInboundPublisher publisher;
    private final Duration retryInitial;
    private final Duration retryMax;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ChatOutboundListener(ChatOutboundHandler handler, ChatInboundPublisher publisher,
                                @Value("${chatwoot.chat.retry-initial}") Duration retryInitial,
                                @Value("${chatwoot.chat.retry-max}") Duration retryMax) {
        this.handler = handler;
        this.publisher = publisher;
        this.retryInitial = retryInitial;
        this.retryMax = retryMax;
    }

    @RabbitListener(queues = ChatQueues.OUTBOUND, containerFactory = ChatListenerConfig.ORDERED,
            autoStartup = "${chatwoot.enabled:false}")
    public void onMessage(Message message) {
        OutboundEvent event;
        try {
            event = json.readValue(message.getBody(), OutboundEvent.class);
        } catch (IOException e) {
            setAside(message, "unreadable JSON: " + e.getMessage());
            return;
        }
        boolean redelivered = Boolean.TRUE.equals(message.getMessageProperties().getRedelivered());
        Duration pause = retryInitial;
        int unknownTries = 0;
        while (true) {
            try {
                handler.handle(event, redelivered);
                return;
            } catch (RuntimeException e) {
                Kind kind = classify(e);
                if (kind == Kind.PERMANENT || (kind == Kind.UNKNOWN && ++unknownTries >= UNKNOWN_ERROR_TRIES)) {
                    setAside(message, event.type() + " " + event.eventId() + ": " + e.getMessage());
                    return;
                }
                log.warn("chat.outbound {} {} of userId={} not applied, retry in {}s: {}", event.type(), event.eventId(),
                        event.userId(), pause.toSeconds(), e.getMessage());
                sleep(pause);
                // after a failure the step may have half happened: treat the next try like a redelivery
                redelivered = true;
                pause = pause.multipliedBy(2).compareTo(retryMax) > 0 ? retryMax : pause.multipliedBy(2);
            }
        }
    }

    enum Kind { TRANSIENT, PERMANENT, UNKNOWN }

    static Kind classify(RuntimeException e) {
        if (e instanceof ChatwootException c) {
            int status = c.getHttpStatus();
            boolean waitable = status == 0 || status >= 500 || status == 429 || status == 401 || status == 403;
            return waitable ? Kind.TRANSIENT : Kind.PERMANENT;
        }
        if (e instanceof NoSuchKeyException || e instanceof IllegalArgumentException) {
            return Kind.PERMANENT;
        }
        // a broken constraint won't heal by waiting: a few tries, then aside
        if (e instanceof DataIntegrityViolationException) {
            return Kind.UNKNOWN;
        }
        if (e instanceof ChatwootDisabledException || e instanceof SdkClientException || e instanceof S3Exception
                || e instanceof AmqpException || e instanceof DataAccessException) {
            return Kind.TRANSIENT;
        }
        return Kind.UNKNOWN;
    }

    private void setAside(Message message, String reason) {
        log.error("chat.outbound event set aside to {}: {}", ChatQueues.OUTBOUND_DEAD, reason);
        publisher.deadOutbound(message);
    }

    /** Interrupted = the container is stopping: the event goes back to the queue, not lost. */
    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImmediateRequeueAmqpException("Stopping while waiting to retry", e);
        }
    }
}
