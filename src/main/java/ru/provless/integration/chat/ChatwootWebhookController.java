package ru.provless.integration.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ru.provless.integration.chatwoot.ChatwootAppInbox;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

/**
 * The app inbox's webhook (plan-chat-connector.md §2.2). Reached only inside the cluster (no Traefik route). Checks
 * Chatwoot's signature — {@code X-Chatwoot-Signature: sha256=HMAC(inbox secret, "<timestamp>.<body>")} — and the age
 * of {@code X-Chatwoot-Timestamp}, then queues the body as is and answers at once: Chatwoot waits only seconds,
 * fetching attachments takes longer.
 */
@Slf4j
@RestController
public class ChatwootWebhookController {

    private final ChatwootAppInbox appInbox;
    private final RabbitTemplate rabbit;
    private final Duration maxAge;
    private final Clock clock;

    @Autowired
    public ChatwootWebhookController(ChatwootAppInbox appInbox, RabbitTemplate rabbit,
                                     @Value("${chatwoot.chat.webhook-max-age}") Duration maxAge) {
        this(appInbox, rabbit, maxAge, Clock.systemUTC());
    }

    ChatwootWebhookController(ChatwootAppInbox appInbox, RabbitTemplate rabbit, Duration maxAge, Clock clock) {
        this.appInbox = appInbox;
        this.rabbit = rabbit;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    @PostMapping("/webhooks/chatwoot/app")
    public ResponseEntity<Void> receive(@RequestBody byte[] body,
                                        @RequestHeader(value = "X-Chatwoot-Signature", required = false) String signature,
                                        @RequestHeader(value = "X-Chatwoot-Timestamp", required = false) String timestamp) {
        if (signature == null || timestamp == null || !fresh(timestamp)) {
            log.warn("Chatwoot app webhook refused: signature or timestamp missing or stale");
            return ResponseEntity.status(401).build();
        }
        // a rotated secret: read it again once before refusing
        if (!signed(body, timestamp, signature, appInbox.webhookSecret(false))
                && !signed(body, timestamp, signature, appInbox.webhookSecret(true))) {
            log.warn("Chatwoot app webhook refused: bad signature");
            return ResponseEntity.status(401).build();
        }
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send("", ChatQueues.WEBHOOK_EVENTS, new Message(body, props));
        return ResponseEntity.ok().build();
    }

    private boolean fresh(String timestamp) {
        try {
            long age = clock.millis() / 1000 - Long.parseLong(timestamp.trim());
            return Math.abs(age) <= maxAge.toSeconds();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean signed(byte[] body, String timestamp, String signature, String secret) {
        if (secret == null || secret.isEmpty()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp.trim() + ".").getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
