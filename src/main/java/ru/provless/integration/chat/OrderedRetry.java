package ru.provless.integration.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import ru.provless.integration.chatwoot.ChatwootDisabledException;
import ru.provless.integration.chatwoot.ChatwootException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;

/**
 * How the chat queues treat a failed event (plan-chat-connector.md §2.1). A queue is applied in order, so an event is
 * retried in place:
 * <ul>
 *   <li>Chatwoot or storage unreachable, 5xx, 429, 401/403 (token or setup) — again after a pause growing to
 *       {@code retry-max}, <b>forever</b>: the queue waits, nothing is lost or reordered;</li>
 *   <li>can't be applied (a 4xx from Chatwoot, a missing file, bad data) — given up at once;</li>
 *   <li>anything else — a few tries, then given up, so a bug can't block the chat forever.</li>
 * </ul>
 */
@Slf4j
@Component
public class OrderedRetry {

    static final int UNKNOWN_ERROR_TRIES = 5;

    private final Duration initial;
    private final Duration max;

    public OrderedRetry(@Value("${chatwoot.chat.retry-initial}") Duration initial,
                        @Value("${chatwoot.chat.retry-max}") Duration max) {
        this.initial = initial;
        this.max = max;
    }

    @FunctionalInterface
    public interface Attempt {
        /** @param retry false on the first try; true after a failure (the step may have half happened) */
        void run(boolean retry);
    }

    /** @return true when applied, false when given up (the caller sets the event aside) */
    public boolean run(String what, Attempt attempt) {
        Duration pause = initial;
        int unknownTries = 0;
        boolean retry = false;
        while (true) {
            try {
                attempt.run(retry);
                return true;
            } catch (RuntimeException e) {
                Kind kind = classify(e);
                if (kind == Kind.PERMANENT || (kind == Kind.UNKNOWN && ++unknownTries >= UNKNOWN_ERROR_TRIES)) {
                    log.error("{} given up: {}", what, e.getMessage(), e);
                    return false;
                }
                log.warn("{} failed, retry in {} ms: {}", what, pause.toMillis(), e.getMessage());
                sleep(pause);
                retry = true;
                pause = pause.multipliedBy(2).compareTo(max) > 0 ? max : pause.multipliedBy(2);
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
        if (e instanceof TransferInProgressException || e instanceof ChatwootDisabledException || e instanceof SdkClientException || e instanceof S3Exception
                || e instanceof AmqpException || e instanceof DataAccessException) {
            return Kind.TRANSIENT;
        }
        return Kind.UNKNOWN;
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
