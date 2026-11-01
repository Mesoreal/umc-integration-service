package ru.provless.integration.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.provless.integration.chatwoot.ChatwootAgent;
import ru.provless.integration.chatwoot.ChatwootClient;
import ru.provless.integration.chatwoot.ChatwootConversation;
import ru.provless.integration.chatwoot.ChatwootInboxSettings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What Chatwoot keeps without telling anyone (plan-app-chat.md §7.1), polled and sent to {@code chat.inbound} only when
 * it changed (plan-chat-connector.md §2.3). A failed poll is logged and tried on the next tick.
 */
@Slf4j
@Component
public class ChatwootPolls {

    /**
     * Chatwoot gives agent_last_seen_at in whole seconds. The user's message and the agent's look can fall in one
     * second; a read time at the end of that second keeps such a message from staying «unread» forever.
     */
    static final long SEEN_ROUNDING_MS = 999;

    private final ChatwootClient chatwoot;
    private final ChatInboxes inboxes;
    private final ChatConversationRepository conversations;
    private final ChatMessageLinkRepository links;
    private final ChatAgentRepository agents;
    private final ChatInboxHoursRepository inboxHours;
    private final ChatInboundPublisher inbound;
    private final boolean enabled;
    private final Clock clock;

    public ChatwootPolls(ChatwootClient chatwoot, ChatInboxes inboxes, ChatConversationRepository conversations,
                         ChatMessageLinkRepository links, ChatAgentRepository agents, ChatInboxHoursRepository inboxHours,
                         ChatInboundPublisher inbound, @Value("${chatwoot.enabled:false}") boolean enabled) {
        this.chatwoot = chatwoot;
        this.inboxes = inboxes;
        this.conversations = conversations;
        this.links = links;
        this.agents = agents;
        this.inboxHours = inboxHours;
        this.inbound = inbound;
        this.enabled = enabled;
        this.clock = Clock.systemUTC();
    }

    // ─── an agent read the user's messages ──────────────────────────────────

    @Scheduled(initialDelayString = "${chatwoot.chat.poll-initial-delay}", fixedDelayString = "${chatwoot.chat.read-poll-interval}")
    public void pollReads() {
        if (!enabled) {
            return;
        }
        for (Long conversationId : links.conversationsAwaitingRead()) {
            try {
                pollRead(conversationId);
            } catch (RuntimeException e) {
                log.warn("Read poll of conversation {} failed: {}", conversationId, e.getMessage());
            }
        }
    }

    void pollRead(long conversationId) {
        Optional<ChatConversation> known = conversations.findById(conversationId);
        Optional<ChatwootConversation> live = chatwoot.findConversation(conversationId);
        if (known.isEmpty() || live.isEmpty() || live.get().agentLastSeenAt() == null) {
            return;
        }
        Instant seenAt = live.get().agentLastSeenAt().plusMillis(SEEN_ROUNDING_MS);
        if (links.markReadUpTo(conversationId, seenAt.atOffset(ZoneOffset.UTC)) == 0) {
            return;
        }
        conversations.setAgentLastSeenAt(conversationId, live.get().agentLastSeenAt().atOffset(ZoneOffset.UTC));
        inbound.send(new InboundEvents.ManagerRead(known.get().getUserId(), seenAt));
        log.debug("Conversation {} read by an agent at {}", conversationId, live.get().agentLastSeenAt());
    }

    // ─── agents online / away / offline ─────────────────────────────────────

    @Scheduled(initialDelayString = "${chatwoot.chat.poll-initial-delay}", fixedDelayString = "${chatwoot.chat.agents-poll-interval}")
    public void pollAgents() {
        if (!enabled) {
            return;
        }
        try {
            Instant now = Instant.now(clock);
            for (ChatwootAgent agent : chatwoot.listAgents()) {
                report(agent, now);
            }
        } catch (RuntimeException e) {
            log.warn("Agents poll failed: {}", e.getMessage());
        }
    }

    private void report(ChatwootAgent agent, Instant now) {
        String presence = ChatwootEventsProcessor.presence(agent.availability());
        ChatAgent known = agents.findById(agent.id()).orElse(null);
        boolean changed = known == null || !presence.equals(known.getAvailability());
        if (known == null) {
            known = new ChatAgent(agent.id(), presence);
        }
        known.setAvailability(presence);
        if ("online".equals(presence)) {
            // «был(а) в сети» = the last time a poll saw them online
            known.setLastSeenAt(now.atOffset(ZoneOffset.UTC));
        }
        agents.save(known);
        if (changed) {
            inbound.send(new InboundEvents.ManagerStatus(InboundEvents.managerId(agent.id()), presence,
                    known.getLastSeenAt() == null ? null : known.getLastSeenAt().toInstant()));
        }
    }

    // ─── working hours ──────────────────────────────────────────────────────

    @Scheduled(initialDelayString = "${chatwoot.chat.poll-initial-delay}", fixedDelayString = "${chatwoot.chat.hours-poll-interval}")
    public void pollHours() {
        if (!enabled) {
            return;
        }
        try {
            for (long inboxId : inboxes.all()) {
                pollHours(inboxId);
            }
        } catch (RuntimeException e) {
            log.warn("Working hours poll failed: {}", e.getMessage());
        }
    }

    /** Each inbox has its own hours (a department's); users get those of their current request's inbox. */
    private void pollHours(long inboxId) {
        ChatwootInboxSettings settings = chatwoot.inboxSettings(inboxId);
        InboundEvents.WorkingHoursChanged hours = hours(null, settings);
        String hash = hash(hours);
        if (inboxHours.findById(inboxId).map(h -> h.getHoursHash().equals(hash)).orElse(false)) {
            return;
        }
        for (UUID userId : conversations.currentUsersOfInbox(inboxId)) {
            inbound.send(hours(userId, settings));
        }
        inboxHours.save(new ChatInboxHours(inboxId, hash));
        log.info("Working hours of inbox {} changed, sent to its users", inboxId);
    }

    /**
     * The hours of the user's inbox to one user — on a new request, before any change happens. Best effort: without
     * them the app shows chat-service's default hours, and the next change of the inbox's hours reaches the user anyway.
     */
    public void sendHours(UUID userId, long inboxId) {
        try {
            inbound.send(hours(userId, chatwoot.inboxSettings(inboxId)));
        } catch (RuntimeException e) {
            log.warn("Working hours not sent to userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * Chatwoot keeps days 0 = Sunday … 6 = Saturday; the contract wants 1 = Monday … 7 = Sunday. Working hours switched
     * off in Chatwoot mean always open; a day missing from the schedule is a day off.
     */
    static InboundEvents.WorkingHoursChanged hours(UUID userId, ChatwootInboxSettings settings) {
        String timezone = settings.timezone() == null || settings.timezone().isBlank() ? "Europe/Moscow" : settings.timezone();
        List<InboundEvents.Day> days = new ArrayList<>();
        for (int weekday = 1; weekday <= 7; weekday++) {
            int chatwootDay = weekday % 7;
            if (!settings.workingHoursEnabled()) {
                days.add(new InboundEvents.Day(weekday, "00:00", "23:59"));
                continue;
            }
            Optional<ChatwootInboxSettings.Day> day = settings.days().stream()
                    .filter(d -> d.dayOfWeek() == chatwootDay).findFirst();
            days.add(new InboundEvents.Day(weekday, day.map(ChatwootInboxSettings.Day::open).orElse(null),
                    day.map(ChatwootInboxSettings.Day::close).orElse(null)));
        }
        return new InboundEvents.WorkingHoursChanged(userId, timezone, days);
    }

    private static String hash(InboundEvents.WorkingHoursChanged hours) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((hours.timezone() + hours.days()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
