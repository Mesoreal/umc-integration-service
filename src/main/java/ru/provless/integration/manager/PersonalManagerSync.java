package ru.provless.integration.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.provless.integration.auth.AuthServiceClient;
import ru.provless.integration.chat.ChatDepartments;
import ru.provless.integration.chat.ChatHeader;
import ru.provless.integration.chat.ChatInboxes;
import ru.provless.integration.chatwoot.AppContactLinkRepository;
import ru.provless.integration.chatwoot.ChatwootClient;
import ru.provless.integration.chatwoot.ChatwootInboxMember;
import ru.provless.integration.manager.DirectusClient.ClientManagerRow;
import ru.provless.integration.manager.DirectusClient.DirectusManager;
import ru.provless.integration.manager.DirectusClient.Editor;
import ru.provless.integration.profile.ContactInfo;
import ru.provless.integration.profile.ProfileServiceClient;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Personal managers between Chatwoot, Directus and the app (docs/backend/artifacts/personal-manager/plan.md §2, §4, §6):
 * agents of the department inboxes → Directus «Менеджеры»; the administration's «Персональные менеджеры клиентов» →
 * {@code personal_manager} with the journal, and back into the row: who the client is in the app, who assigned and when;
 * the client's chat header and the Chatwoot contact attribute «Персональный менеджер» follow. A failed poll (or row) is
 * logged and tried on the next tick.
 */
@Slf4j
@Component
public class PersonalManagerSync {

    static final String NO_ACCOUNT = "нет аккаунта с этим номером";
    static final String NAMELESS = "аккаунт есть, имя не заполнено";
    static final String ACCOUNT = "аккаунт есть";
    /** «Назначил» when the last change of the row is the service's own (it writes into the row too). */
    static final String UNKNOWN_EDITOR = "не определено — см. «Ревизии»";
    static final String ROW_DELETED = "строка удалена в CMS";
    /** Shown to agents only; editing it in Chatwoot changes nothing (the next change of the assignment writes it again). */
    static final String CONTACT_ATTRIBUTE = "personal_manager";

    private final DirectusClient directus;
    private final ChatwootClient chatwoot;
    private final ChatInboxes inboxes;
    private final ChatDepartments departments;
    private final AuthServiceClient auth;
    private final ProfileServiceClient profiles;
    private final PersonalManagerRepository assignments;
    private final PersonalManagerHistoryRepository history;
    private final TransactionTemplate tx;
    private final ChatHeader header;
    private final AppContactLinkRepository contacts;
    private final boolean chatwootEnabled;
    private final String serviceEmail;
    private final Clock clock = Clock.systemUTC();
    private volatile boolean attributeDefined;

    public PersonalManagerSync(DirectusClient directus, ChatwootClient chatwoot, ChatInboxes inboxes,
                               ChatDepartments departments, AuthServiceClient auth, ProfileServiceClient profiles,
                               PersonalManagerRepository assignments, PersonalManagerHistoryRepository history,
                               TransactionTemplate tx, ChatHeader header, AppContactLinkRepository contacts,
                               @Value("${chatwoot.enabled:false}") boolean chatwootEnabled,
                               @Value("${directus.service-email}") String serviceEmail) {
        this.directus = directus;
        this.chatwoot = chatwoot;
        this.inboxes = inboxes;
        this.departments = departments;
        this.auth = auth;
        this.profiles = profiles;
        this.assignments = assignments;
        this.history = history;
        this.tx = tx;
        this.header = header;
        this.contacts = contacts;
        this.chatwootEnabled = chatwootEnabled;
        this.serviceEmail = serviceEmail;
    }

    // ─── agents of the departments → «Менеджеры» ────────────────────────────

    @Scheduled(initialDelayString = "${personal-manager.poll-initial-delay}",
            fixedDelayString = "${personal-manager.managers-poll-interval}")
    public void syncManagers() {
        if (!chatwootEnabled || !directus.enabled()) {
            return;
        }
        try {
            Map<Long, DirectusManager> wanted = agentsOfDepartments();
            Map<Long, DirectusManager> known = directus.managers();
            for (DirectusManager manager : wanted.values()) {
                DirectusManager was = known.get(manager.id());
                if (was == null) {
                    directus.createManager(manager);
                    log.info("Manager {} '{}' added", manager.id(), manager.name());
                } else if (!was.equals(manager)) {
                    directus.updateManager(manager);
                    log.info("Manager {} '{}' updated", manager.id(), manager.name());
                }
            }
            for (DirectusManager was : known.values()) {
                if (was.active() && !wanted.containsKey(was.id())) {
                    // kept: assignments to them stay visible, to be reassigned
                    directus.updateManager(new DirectusManager(was.id(), was.name(), was.email(), was.departments(), false,
                            was.avatarUrl()));
                    log.info("Manager {} '{}' is in no department inbox now: inactive", was.id(), was.name());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Managers sync failed: {}", e.getMessage());
        }
    }

    /** Every agent of a department inbox; one in both — both departments. All inboxes are read before anything is written. */
    private Map<Long, DirectusManager> agentsOfDepartments() {
        Map<Long, ChatwootInboxMember> agents = new LinkedHashMap<>();
        Map<Long, List<String>> agentDepartments = new HashMap<>();
        for (ChatDepartments.Department department : departments.departments()) {
            for (ChatwootInboxMember member : chatwoot.inboxMembers(inboxes.departmentInboxId(department))) {
                agents.putIfAbsent(member.id(), member);
                agentDepartments.computeIfAbsent(member.id(), id -> new ArrayList<>()).add(department.inboxName());
            }
        }
        Map<Long, DirectusManager> managers = new LinkedHashMap<>();
        agents.forEach((id, agent) -> managers.put(id, new DirectusManager(id, agent.name(), agent.email(),
                String.join(", ", agentDepartments.get(id)), true, agent.thumbnail())));
        return managers;
    }

    // ─── «Персональные менеджеры клиентов» → personal_manager ───────────────

    @Scheduled(initialDelayString = "${personal-manager.poll-initial-delay}",
            fixedDelayString = "${personal-manager.assignments-poll-interval}")
    public void syncAssignments() {
        if (!directus.enabled()) {
            return;
        }
        List<ClientManagerRow> rows;
        try {
            rows = directus.clientManagers();
        } catch (RuntimeException e) {
            log.warn("Personal managers not read from Directus: {}", e.getMessage());
            return;
        }
        Map<UUID, PersonalManager> known = new HashMap<>();
        assignments.findAll().forEach(pm -> known.put(pm.getDirectusId(), pm));
        for (ClientManagerRow row : rows) {
            PersonalManager pm = known.remove(row.id());
            if (row.phone() == null) {
                continue;
            }
            try {
                sync(row, pm);
            } catch (RuntimeException e) {
                log.warn("Personal manager row {} not synced: {}", row.id(), e.getMessage());
            }
        }
        known.values().forEach(this::rowDeleted);
    }

    private void sync(ClientManagerRow row, PersonalManager known) {
        UUID userId = auth.findUserByPhone(row.phone()).orElse(null);
        String client = userId == null ? NO_ACCOUNT : clientName(userId, row.client());

        PersonalManager pm = known != null ? known : new PersonalManager(row.id());
        UUID previousUser = pm.getUserId();
        boolean managerChanged = known == null ? row.managerId() != null : !Objects.equals(known.getAgentId(), row.managerId());
        if (managerChanged) {
            // the last change of the row is the one that set this manager: the service writes only after it has seen it
            Editor editor = row.updatedBy() != null ? row.updatedBy() : row.createdBy();
            Instant at = row.updatedAt() != null ? row.updatedAt() : row.createdAt();
            pm.setAgentId(row.managerId());
            pm.setAssignedBy(editor == null || serviceEmail.equalsIgnoreCase(editor.email()) ? UNKNOWN_EDITOR : editor.label());
            pm.setAssignedAt((at != null ? at : Instant.now(clock).truncatedTo(ChronoUnit.SECONDS)).atOffset(ZoneOffset.UTC));
        }
        boolean userFound = userId != null && !userId.equals(previousUser);
        pm.setUserId(userId);
        if (managerChanged || userFound || !Objects.equals(pm.getAgentName(), row.managerName())) {
            pm.setAgentName(row.managerName());
            pm.setNotified(false);
        }
        tx.executeWithoutResult(status -> {
            assignments.save(pm);
            if (managerChanged) {
                history.save(new PersonalManagerHistory(row.id(), userId, row.managerId(), row.managerName(),
                        pm.getAssignedBy(), pm.getAssignedAt()));
            }
            if (userFound) {
                history.attachUser(row.id(), userId);
            }
        });
        if (managerChanged || userFound) {
            log.info("Personal manager row {}: userId={} agent={} by {}", row.id(), userId, pm.getAgentId(), pm.getAssignedBy());
        }
        if (previousUser != null && !previousUser.equals(userId)) {
            // the phone is someone else's now (or nobody's): the former client has no personal manager
            notifyBestEffort(previousUser);
        }
        if (userId != null && !pm.isNotified()) {
            notify(userId, pm.getAgentId() == null ? null : pm.getAgentName());
            pm.setNotified(true);
            assignments.save(pm);
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        if (!Objects.equals(row.client(), client)) {
            patch.put("client", client);
        }
        if (!Objects.equals(row.appUserId(), userId)) {
            patch.put("app_user_id", userId == null ? null : userId.toString());
        }
        if (pm.getAssignedBy() != null && !pm.getAssignedBy().equals(row.assignedBy())) {
            patch.put("assigned_by", pm.getAssignedBy());
        }
        Instant assignedAt = pm.getAssignedAt() == null ? null : pm.getAssignedAt().toInstant().truncatedTo(ChronoUnit.MILLIS);
        if (assignedAt != null && (row.assignedAt() == null || !assignedAt.equals(row.assignedAt().truncatedTo(ChronoUnit.MILLIS)))) {
            patch.put("assigned_at", assignedAt.toString());
        }
        if (!patch.isEmpty()) {
            directus.updateClientManager(row.id(), patch);
        }
    }

    /** The client's name from the profile; a failed read keeps what the row shows. */
    private String clientName(UUID userId, String shown) {
        try {
            return profiles.findContactInfo(userId).map(ContactInfo::fullName).filter(name -> !name.isBlank()).orElse(NAMELESS);
        } catch (RuntimeException e) {
            log.warn("Profile of userId={} not read: {}", userId, e.getMessage());
            return shown == null || NO_ACCOUNT.equals(shown) ? ACCOUNT : shown;
        }
    }

    private void rowDeleted(PersonalManager pm) {
        tx.executeWithoutResult(status -> {
            assignments.delete(pm);
            if (pm.getAgentId() != null) {
                history.save(new PersonalManagerHistory(pm.getDirectusId(), pm.getUserId(), null, null, ROW_DELETED,
                        OffsetDateTime.now(clock)));
            }
        });
        log.info("Personal manager row {} deleted in Directus: userId={} had agent={}", pm.getDirectusId(), pm.getUserId(),
                pm.getAgentId());
        if (pm.getUserId() != null) {
            notifyBestEffort(pm.getUserId());
        }
    }

    // ─── the client's chat header and Chatwoot contact ──────────────────────

    /** {@code managerName} null — no personal manager. */
    private void notify(UUID userId, String managerName) {
        header.refresh(userId);
        if (!chatwootEnabled) {
            return;
        }
        contacts.findById(userId).ifPresent(link -> {
            if (managerName == null) {
                chatwoot.removeContactAttributes(link.getContactId(), List.of(CONTACT_ATTRIBUTE));
                return;
            }
            defineAttribute();
            chatwoot.updateContact(link.getContactId(), null, Map.of(CONTACT_ATTRIBUTE, managerName));
        });
    }

    /** A client who lost the personal manager with their row: nothing keeps a retry, a failure is only logged. */
    private void notifyBestEffort(UUID userId) {
        try {
            notify(userId, null);
        } catch (RuntimeException e) {
            log.warn("userId={} not told the personal manager is gone: {}", userId, e.getMessage());
        }
    }

    private void defineAttribute() {
        if (attributeDefined) {
            return;
        }
        if (!chatwoot.contactAttributeDefined(CONTACT_ATTRIBUTE)) {
            chatwoot.defineContactAttribute(CONTACT_ATTRIBUTE, "Персональный менеджер",
                    "Назначает администрация в CMS; правка здесь ни на что не влияет");
            log.info("Chatwoot contact attribute '{}' defined", CONTACT_ATTRIBUTE);
        }
        attributeDefined = true;
    }
}
