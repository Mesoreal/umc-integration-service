package ru.provless.integration.manager;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import com.jayway.jsonpath.JsonPath;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import ru.provless.integration.account.AccountDeletedListener;
import ru.provless.integration.chat.ChatConnectorTestBase;
import ru.provless.integration.chat.ChatQueues;

import java.nio.charset.StandardCharsets;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/** Personal managers (plan.md §2, §4, §6): WireMock as Chatwoot, Directus, auth-service and profile-service. */
@TestPropertySource(properties = {
        "personal-manager.poll-initial-delay=PT1H",
        "directus.token=test-directus-token"
})
class PersonalManagerSyncIT extends ChatConnectorTestBase {

    static final String ITEMS = "/directus/items/";
    static final UUID ROW_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID ROW_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    static final String ANNA = """
            {"first_name":"Анна","last_name":"Петрова","email":"anna@mesoreal.ru"}""";
    static final String SERVICE = """
            {"first_name":"integration-service","last_name":null,"email":"integration-service@mesoreal.ru"}""";

    static WireMockServer services = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        services.start();
    }

    @DynamicPropertySource
    static void serviceEndpoints(DynamicPropertyRegistry registry) {
        registry.add("directus.url", () -> services.baseUrl() + "/directus");
        registry.add("auth-service.base-url", services::baseUrl);
        registry.add("profile-service.base-url", services::baseUrl);
    }

    @Autowired
    PersonalManagerSync sync;
    @Autowired
    AccountDeletedListener accountDeleted;

    private UUID ivan;

    @BeforeEach
    void setUp() {
        services.resetAll();
        jdbc.update("TRUNCATE chatwoot.personal_manager, chatwoot.personal_manager_history");
        ivan = UUID.randomUUID();
        services.stubFor(patch(urlPathMatching("/directus/items/.*")).willReturn(okJson("{\"data\":{}}")));
        services.stubFor(post(urlPathMatching("/directus/items/.*")).willReturn(okJson("{\"data\":{}}")));
        services.stubFor(get(urlPathEqualTo("/internal/users/by-phone")).willReturn(aResponse().withStatus(404)));
        services.stubFor(get(urlPathEqualTo("/internal/users/by-phone")).withQueryParam("phone", equalTo("+79990000001"))
                .withHeader("X-Internal-Auth", equalTo("dev-internal-api-key-change-me"))
                .willReturn(okJson("{\"user_id\":\"" + ivan + "\"}")));
        services.stubFor(get("/internal/profiles/" + ivan + "/contact-info")
                .willReturn(okJson("{\"phone\":\"+79990000001\",\"fullName\":\"Иванов Иван\"}")));
        // Ivan's Chatwoot contact; the attribute «Персональный менеджер» is not defined yet
        jdbc.update("INSERT INTO chatwoot.app_contact_link (user_id, contact_id) VALUES (?, 42)", ivan);
        chatwoot.stubFor(get(urlPathEqualTo(ACCOUNT + "/custom_attribute_definitions")).willReturn(okJson("[]")));
        chatwoot.stubFor(post(ACCOUNT + "/custom_attribute_definitions").willReturn(okJson("{\"id\":1}")));
        chatwoot.stubFor(put(ACCOUNT + "/contacts/42").willReturn(okJson("{\"payload\":{\"id\":42}}")));
        chatwoot.stubFor(post(ACCOUNT + "/contacts/42/destroy_custom_attributes").willReturn(okJson("{}")));
        org.springframework.test.util.ReflectionTestUtils.setField(sync, "attributeDefined", false);
        drain(ChatQueues.INBOUND);
    }

    // ─── «Менеджеры» ────────────────────────────────────────────────────────

    @Test
    void managers_newAdded_changedUpdated_goneInactive_sameUntouched() {
        // 1 — sales only (new); 2 — both (renamed); 4 — UMC, unchanged; 3 — left the departments
        chatwoot.stubFor(get(ACCOUNT + "/inbox_members/6").willReturn(okJson("""
                {"payload":[{"id":1,"name":"Ольга","available_name":"Ольга С.","email":"olga@m.ru","thumbnail":"https://cw/a1"},
                            {"id":2,"name":"Пётр","email":"petr@m.ru","thumbnail":""}]}""")));
        chatwoot.stubFor(get(ACCOUNT + "/inbox_members/7").willReturn(okJson("""
                {"payload":[{"id":2,"name":"Пётр","email":"petr@m.ru","thumbnail":""},
                            {"id":4,"name":"Вера","email":"vera@m.ru","thumbnail":null}]}""")));
        services.stubFor(get(urlPathEqualTo(ITEMS + "managers")).willReturn(okJson("""
                {"data":[{"id":2,"name":"Петя","email":"petr@m.ru","departments":"Приложение — продажи","active":true,"avatar_url":null},
                         {"id":3,"name":"Семён","email":"sem@m.ru","departments":"Приложение — УМЦ","active":true,"avatar_url":null},
                         {"id":4,"name":"Вера","email":"vera@m.ru","departments":"Приложение — УМЦ","active":true,"avatar_url":null}]}""")));

        sync.syncManagers();

        services.verify(postRequestedFor(urlEqualTo(ITEMS + "managers")).withRequestBody(equalToJson("""
                {"id":1,"name":"Ольга С.","email":"olga@m.ru","departments":"Приложение — продажи","active":true,
                 "avatar_url":"https://cw/a1"}""")));
        services.verify(patchRequestedFor(urlEqualTo(ITEMS + "managers/2")).withRequestBody(equalToJson("""
                {"name":"Пётр","email":"petr@m.ru","departments":"Приложение — продажи, Приложение — УМЦ","active":true,
                 "avatar_url":null}""")));
        services.verify(patchRequestedFor(urlEqualTo(ITEMS + "managers/3")).withRequestBody(equalToJson("""
                {"name":"Семён","email":"sem@m.ru","departments":"Приложение — УМЦ","active":false,"avatar_url":null}""")));
        services.verify(0, patchRequestedFor(urlEqualTo(ITEMS + "managers/4")));
    }

    @Test
    void managers_chatwootDown_nothingWritten() {
        chatwoot.stubFor(get(ACCOUNT + "/inbox_members/6").willReturn(okJson("{\"payload\":[]}")));
        chatwoot.stubFor(get(ACCOUNT + "/inbox_members/7").willReturn(aResponse().withStatus(502)));
        services.stubFor(get(urlPathEqualTo(ITEMS + "managers")).willReturn(okJson("""
                {"data":[{"id":3,"name":"Семён","email":"sem@m.ru","departments":"Приложение — УМЦ","active":true}]}""")));

        sync.syncManagers();

        services.verify(0, patchRequestedFor(urlPathMatching(ITEMS + "managers/.*")));
    }

    // ─── «Персональные менеджеры клиентов» ──────────────────────────────────

    @Test
    void assignment_withAccount_andWithout_thenAccountAppears() {
        rows(row(ROW_A, "+79990000001", 2, "Пётр", null, null, null, null, ANNA, "2026-11-02T10:00:00.000Z", null, null),
                row(ROW_B, "+79990000002", 1, "Ольга", null, null, null, null, ANNA, "2026-11-02T10:05:00.000Z", null, null));

        sync.syncAssignments();

        services.verify(patchRequestedFor(urlEqualTo(ITEMS + "client_managers/" + ROW_A)).withRequestBody(equalToJson(
                "{\"client\":\"Иванов Иван\",\"app_user_id\":\"" + ivan + "\",\"assigned_by\":\"Анна Петрова (anna@mesoreal.ru)\","
                        + "\"assigned_at\":\"2026-11-02T10:00:00Z\"}")));
        services.verify(patchRequestedFor(urlEqualTo(ITEMS + "client_managers/" + ROW_B)).withRequestBody(equalToJson(
                "{\"client\":\"нет аккаунта с этим номером\",\"assigned_by\":\"Анна Петрова (anna@mesoreal.ru)\","
                        + "\"assigned_at\":\"2026-11-02T10:05:00Z\"}")));
        assertThat(jdbc.queryForList("SELECT user_id, agent_id FROM chatwoot.personal_manager ORDER BY directus_id"))
                .containsExactly(Map.of("user_id", ivan, "agent_id", 2L), nullUser(1L));
        assertThat(history()).hasSize(2);

        // the service's own write is now the last change of the rows: nothing to do
        services.resetRequests();
        rows(row(ROW_A, "+79990000001", 2, "Пётр", "Иванов Иван", ivan, "Анна Петрова (anna@mesoreal.ru)",
                        "2026-11-02T10:00:00.000Z", ANNA, "2026-11-02T10:00:00.000Z", SERVICE, "2026-11-02T10:01:00.000Z"),
                row(ROW_B, "+79990000002", 1, "Ольга", "нет аккаунта с этим номером", null, "Анна Петрова (anna@mesoreal.ru)",
                        "2026-11-02T10:05:00.000Z", ANNA, "2026-11-02T10:05:00.000Z", SERVICE, "2026-11-02T10:06:00.000Z"));
        sync.syncAssignments();
        services.verify(0, patchRequestedFor(urlPathMatching(ITEMS + "client_managers/.*")));
        assertThat(history()).hasSize(2);

        // the second phone registers: the row and its journal become that client's, the assignment stays Anna's
        UUID olga = UUID.randomUUID();
        services.stubFor(get(urlPathEqualTo("/internal/users/by-phone")).withQueryParam("phone", equalTo("+79990000002"))
                .willReturn(okJson("{\"user_id\":\"" + olga + "\"}")));
        services.stubFor(get("/internal/profiles/" + olga + "/contact-info").willReturn(aResponse().withStatus(404)));
        sync.syncAssignments();
        services.verify(patchRequestedFor(urlEqualTo(ITEMS + "client_managers/" + ROW_B)).withRequestBody(equalToJson(
                "{\"client\":\"аккаунт есть, имя не заполнено\",\"app_user_id\":\"" + olga + "\"}")));
        assertThat(jdbc.queryForObject("SELECT user_id FROM chatwoot.personal_manager_history WHERE directus_id = ?",
                UUID.class, ROW_B)).isEqualTo(olga);
    }

    @Test
    void managerChanged_journaled_withWhoChanged_rowDeleted_journaledAsTakenOff() {
        rows(row(ROW_A, "+79990000001", 2, "Пётр", null, null, null, null, ANNA, "2026-11-02T10:00:00.000Z", null, null));
        sync.syncAssignments();

        services.resetRequests();
        rows(row(ROW_A, "+79990000001", 4, "Вера", "Иванов Иван", ivan, "Анна Петрова (anna@mesoreal.ru)",
                "2026-11-02T10:00:00.000Z", ANNA, "2026-11-02T10:00:00.000Z",
                "{\"first_name\":\"Борис\",\"last_name\":\"\",\"email\":\"boris@mesoreal.ru\"}", "2026-11-03T09:30:00.000Z"));
        sync.syncAssignments();

        services.verify(patchRequestedFor(urlEqualTo(ITEMS + "client_managers/" + ROW_A)).withRequestBody(equalToJson("""
                {"assigned_by":"Борис (boris@mesoreal.ru)","assigned_at":"2026-11-03T09:30:00Z"}""")));
        assertThat(jdbc.queryForObject("SELECT agent_id FROM chatwoot.personal_manager WHERE user_id = ?", Long.class, ivan))
                .isEqualTo(4L);

        rows();
        sync.syncAssignments();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chatwoot.personal_manager", Integer.class)).isZero();
        assertThat(history()).extracting(h -> h.get("agent_name") + " / " + h.get("assigned_by")).containsExactly(
                "Пётр / Анна Петрова (anna@mesoreal.ru)", "Вера / Борис (boris@mesoreal.ru)", "null / строка удалена в CMS");
        assertThat(history()).allMatch(h -> ivan.equals(h.get("user_id")));
    }

    @Test
    void authDown_rowSkipped_triedNextTime() {
        services.stubFor(get(urlPathEqualTo("/internal/users/by-phone")).willReturn(aResponse().withStatus(503)));
        rows(row(ROW_A, "+79990000001", 2, "Пётр", null, null, null, null, ANNA, "2026-11-02T10:00:00.000Z", null, null));

        sync.syncAssignments();

        services.verify(0, patchRequestedFor(urlPathMatching(ITEMS + "client_managers/.*")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chatwoot.personal_manager", Integer.class)).isZero();
    }

    // ─── the chat header and the Chatwoot contact ───────────────────────────

    @Test
    void assigned_headerShowsPersonal_contactAttributeWritten_removedWithRow() {
        jdbc.update("INSERT INTO chatwoot.chat_agent (agent_id, availability, name, avatar_url) VALUES (2, 'online', 'Пётр', "
                + "'https://cw/2.png')");
        rows(row(ROW_A, "+79990000001", 2, "Пётр", null, null, null, null, ANNA, "2026-11-02T10:00:00.000Z", null, null));

        sync.syncAssignments();

        String changed = receiveUntil(ChatQueues.INBOUND, b -> b.contains("manager.changed"), 10_000).getLast();
        assertThat((String) JsonPath.read(changed, "$.user_id")).isEqualTo(ivan.toString());
        assertThat((String) JsonPath.read(changed, "$.manager.id")).isEqualTo("cw:2");
        assertThat((String) JsonPath.read(changed, "$.manager.name")).isEqualTo("Пётр");
        assertThat((String) JsonPath.read(changed, "$.manager.avatar_url")).isEqualTo("https://cw/2.png");
        assertThat((String) JsonPath.read(changed, "$.manager.status")).isEqualTo("online");
        assertThat((Boolean) JsonPath.read(changed, "$.manager.is_personal")).isTrue();
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/custom_attribute_definitions")).withRequestBody(equalToJson("""
                {"custom_attribute_definition":{"attribute_key":"personal_manager","attribute_display_name":"Персональный менеджер",
                 "attribute_display_type":"text","attribute_model":"contact_attribute"}}""", true, true)));
        chatwoot.verify(putRequestedFor(urlEqualTo(ACCOUNT + "/contacts/42")).withRequestBody(equalToJson("""
                {"custom_attributes":{"personal_manager":"Пётр"}}""")));

        // sent once: the next poll changes nothing
        chatwoot.resetRequests();
        sync.syncAssignments();
        chatwoot.verify(0, putRequestedFor(urlEqualTo(ACCOUNT + "/contacts/42")));

        rows();
        sync.syncAssignments();
        String gone = receiveUntil(ChatQueues.INBOUND, b -> b.contains("manager.changed"), 10_000).getLast();
        assertThat((Object) JsonPath.read(gone, "$.manager")).isNull();
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/contacts/42/destroy_custom_attributes"))
                .withRequestBody(equalToJson("{\"custom_attributes\":[\"personal_manager\"]}")));
    }

    @Test
    void requestHandledBySomeoneElse_headerShowsThem_notPersonal() {
        jdbc.update("INSERT INTO chatwoot.chat_agent (agent_id, availability, name) VALUES (4, 'busy', 'Вера')");
        jdbc.update("INSERT INTO chatwoot.chat_conversation (conversation_id, user_id, contact_id, inbox_id, status, current, "
                + "assignee_id) VALUES (15, ?, 42, 6, 'open', true, 4)", ivan);
        rows(row(ROW_A, "+79990000001", 2, "Пётр", null, null, null, null, ANNA, "2026-11-02T10:00:00.000Z", null, null));

        sync.syncAssignments();

        String changed = receiveUntil(ChatQueues.INBOUND, b -> b.contains("manager.changed"), 10_000).getLast();
        assertThat((String) JsonPath.read(changed, "$.manager.id")).isEqualTo("cw:4");
        assertThat((String) JsonPath.read(changed, "$.manager.name")).isEqualTo("Вера");
        assertThat((Boolean) JsonPath.read(changed, "$.manager.is_personal")).isFalse();
    }

    @Test
    void accountDeleted_rowGoesFromDirectus_journalStays() {
        jdbc.update("DELETE FROM chatwoot.app_contact_link WHERE user_id = ?", ivan);
        rows(row(ROW_A, "+79990000001", 2, "Пётр", null, null, null, null, ANNA, "2026-11-02T10:00:00.000Z", null, null));
        sync.syncAssignments();
        services.stubFor(delete(ITEMS + "client_managers/" + ROW_A).willReturn(aResponse().withStatus(204)));

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        accountDeleted.onDeleted(new Message(("{\"user_id\":\"" + ivan + "\"}").getBytes(StandardCharsets.UTF_8), props));

        services.verify(deleteRequestedFor(urlEqualTo(ITEMS + "client_managers/" + ROW_A)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chatwoot.personal_manager", Integer.class)).isZero();
        assertThat(history()).extracting(h -> h.get("assigned_by")).containsExactly(
                "Анна Петрова (anna@mesoreal.ru)", "аккаунт удалён");
        assertThat(history()).allMatch(h -> ivan.equals(h.get("user_id")));
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private void rows(String... rows) {
        services.stubFor(get(urlPathEqualTo(ITEMS + "client_managers"))
                .withHeader("Authorization", equalTo("Bearer test-directus-token"))
                .willReturn(okJson("{\"data\":[" + String.join(",", rows) + "]}")));
    }

    private static String row(UUID id, String phone, long manager, String managerName, String client, UUID appUserId,
                              String assignedBy, String assignedAt, String createdBy, String createdAt,
                              String updatedBy, String updatedAt) {
        return "{\"id\":\"" + id + "\",\"phone\":\"" + phone + "\",\"manager\":{\"id\":" + manager + ",\"name\":\"" + managerName
                + "\"},\"client\":" + str(client) + ",\"app_user_id\":" + str(appUserId == null ? null : appUserId.toString())
                + ",\"assigned_by\":" + str(assignedBy) + ",\"assigned_at\":" + str(assignedAt)
                + ",\"user_created\":" + createdBy + ",\"date_created\":" + str(createdAt)
                + ",\"user_updated\":" + updatedBy + ",\"date_updated\":" + str(updatedAt) + "}";
    }

    private static String str(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static Map<String, Object> nullUser(long agent) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("user_id", null);
        m.put("agent_id", agent);
        return m;
    }

    private List<Map<String, Object>> history() {
        return jdbc.queryForList("SELECT * FROM chatwoot.personal_manager_history ORDER BY id");
    }
}
