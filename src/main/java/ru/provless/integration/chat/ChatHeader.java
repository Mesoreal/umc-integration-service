package ru.provless.integration.chat;

import org.springframework.stereotype.Component;
import ru.provless.integration.manager.PersonalManager;
import ru.provless.integration.manager.PersonalManagerRepository;

import java.util.UUID;

/**
 * Who the app shows in the chat header (contract §14 «Manager», personal-manager/plan.md §3): the agent of the current
 * request, {@code is_personal} when that is the client's personal manager; nobody assigned — the personal manager;
 * neither — nobody («Ваш менеджер»).
 */
@Component
public class ChatHeader {

    private final ChatConversationRepository conversations;
    private final ChatAgentRepository agents;
    private final PersonalManagerRepository personalManagers;
    private final ChatInboundPublisher inbound;

    public ChatHeader(ChatConversationRepository conversations, ChatAgentRepository agents,
                      PersonalManagerRepository personalManagers, ChatInboundPublisher inbound) {
        this.conversations = conversations;
        this.agents = agents;
        this.personalManagers = personalManagers;
        this.inbound = inbound;
    }

    /**
     * {@code assigneeId} — the agent of the user's current request (null — nobody); {@code name} and {@code presence}
     * as the caller has them fresh from Chatwoot, null — what the agents poll last saw.
     */
    public void send(UUID userId, Long assigneeId, String name, String presence) {
        inbound.send(new InboundEvents.ManagerChanged(userId, manager(userId, assigneeId, name, presence)));
    }

    /** Again from what is stored, for everyone who sees this agent in the header: the agent's role changed. */
    public void refreshAgent(long agentId) {
        java.util.Set<UUID> users = new java.util.LinkedHashSet<>(conversations.currentUsersOfAgent(agentId));
        personalManagers.findByAgentId(agentId).forEach(pm -> {
            if (pm.getUserId() != null) {
                users.add(pm.getUserId());
            }
        });
        users.forEach(this::refresh);
    }

    /** Again from what is stored: the personal manager changed. */
    public void refresh(UUID userId) {
        Long assignee = conversations.findByUserIdAndCurrentTrue(userId).map(ChatConversation::getAssigneeId).orElse(null);
        send(userId, assignee, null, null);
    }

    InboundEvents.Manager manager(UUID userId, Long assigneeId, String name, String presence) {
        PersonalManager personal = personalManagers.findByUserId(userId).filter(pm -> pm.getAgentId() != null).orElse(null);
        Long agentId = assigneeId != null ? assigneeId : personal == null ? null : personal.getAgentId();
        if (agentId == null) {
            return null;
        }
        boolean isPersonal = personal != null && agentId.equals(personal.getAgentId());
        ChatAgent agent = agents.findById(agentId).orElse(null);
        if (name == null) {
            name = agent != null && agent.getName() != null ? agent.getName() : isPersonal ? personal.getAgentName() : null;
        }
        if (presence == null) {
            presence = agent == null ? "offline" : agent.getAvailability();
        }
        return new InboundEvents.Manager(InboundEvents.managerId(agentId), name, agent == null ? null : agent.getAvatarUrl(),
                isPersonal, presence, agent == null || agent.getLastSeenAt() == null ? null : agent.getLastSeenAt().toInstant(),
                agent == null ? null : agent.getRole());
    }
}
