package ru.provless.integration.chat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

class ChatwootPollsIT extends ChatConnectorTestBase {

    @Autowired
    ChatwootPolls polls;

    private UUID user;

    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        drain(ChatQueues.INBOUND);
        jdbc.update("INSERT INTO chatwoot.chat_conversation (conversation_id, user_id, contact_id, inbox_id, status, current) "
                + "VALUES (15, ?, 42, 5, 'open', true)", user);
    }

    @Test
    void read_sentOnceForWhatTheAgentSaw() {
        Instant seen = Instant.now().minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        link(801, seen.minusSeconds(20));
        link(802, seen.plusMillis(400));      // the same second as the look: counted as seen
        link(803, seen.plusSeconds(30));      // written after the look
        chatwoot.stubFor(get(ACCOUNT + "/conversations/15").willReturn(okJson(
                "{\"id\":15,\"inbox_id\":5,\"status\":\"open\",\"agent_last_seen_at\":" + seen.getEpochSecond() + "}")));

        polls.pollReads();

        String read = receiveUntil(ChatQueues.INBOUND, b -> b.contains("manager.read"), 5000).getLast();
        assertThat((String) JsonPath.read(read, "$.user_id")).isEqualTo(user.toString());
        assertThat(Instant.parse(JsonPath.read(read, "$.read_at"))).isEqualTo(seen.plusMillis(999));
        assertThat(jdbc.queryForList("SELECT cw_message_id FROM chatwoot.chat_message_link WHERE read_by_manager "
                + "ORDER BY cw_message_id", Long.class)).containsExactly(801L, 802L);

        // nothing new seen: nothing sent
        polls.pollReads();
        assertThat(rabbit.receive(ChatQueues.INBOUND, 1000)).isNull();
    }

    @Test
    void agents_onlyChanges() {
        chatwoot.stubFor(get(ACCOUNT + "/agents").willReturn(okJson("""
                [{"id":7,"available_name":"Анна","availability_status":"online"},
                 {"id":8,"available_name":"Олег","availability_status":"busy"}]""")));
        polls.pollAgents();
        List<String> first = receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:8"), 5000);
        assertThat(String.join("", first)).contains("\"manager_id\":\"cw:7\"").contains("\"status\":\"online\"")
                .contains("\"status\":\"away\"");

        polls.pollAgents();
        assertThat(rabbit.receive(ChatQueues.INBOUND, 1000)).isNull();

        chatwoot.stubFor(get(ACCOUNT + "/agents").willReturn(okJson("""
                [{"id":7,"available_name":"Анна","availability_status":"offline"},
                 {"id":8,"available_name":"Олег","availability_status":"busy"}]""")));
        polls.pollAgents();
        String offline = receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:7"), 5000).getLast();
        assertThat((String) JsonPath.read(offline, "$.status")).isEqualTo("offline");
        // «был(а) в сети» — when the previous poll still saw her online
        assertThat((String) JsonPath.read(offline, "$.last_seen_at")).isNotNull();
    }

    @Test
    void hours_toUsersOfTheInbox_onChangeOnly() {
        chatwoot.stubFor(get(ACCOUNT + "/inboxes/5").willReturn(okJson("""
                {"id":5,"working_hours_enabled":true,"timezone":"Asia/Yekaterinburg","working_hours":[
                  {"day_of_week":0,"closed_all_day":true},
                  {"day_of_week":1,"closed_all_day":false,"open_hour":9,"open_minutes":0,"close_hour":18,"close_minutes":0},
                  {"day_of_week":6,"closed_all_day":false,"open_all_day":true}]}""")));

        polls.pollHours();

        String hours = receiveUntil(ChatQueues.INBOUND, b -> b.contains("working_hours.changed"), 5000).getLast();
        assertThat((String) JsonPath.read(hours, "$.user_id")).isEqualTo(user.toString());
        assertThat((String) JsonPath.read(hours, "$.timezone")).isEqualTo("Asia/Yekaterinburg");
        assertThat((Integer) JsonPath.read(hours, "$.days[0].weekday")).isEqualTo(1);
        assertThat((String) JsonPath.read(hours, "$.days[0].open")).isEqualTo("09:00");
        assertThat((String) JsonPath.read(hours, "$.days[1].open")).isNull();        // Tuesday not in the schedule
        assertThat((String) JsonPath.read(hours, "$.days[5].close")).isEqualTo("23:59"); // Saturday all day
        assertThat((Integer) JsonPath.read(hours, "$.days[6].weekday")).isEqualTo(7);   // Sunday, closed
        assertThat((String) JsonPath.read(hours, "$.days[6].open")).isNull();

        polls.pollHours();
        assertThat(rabbit.receive(ChatQueues.INBOUND, 1000)).isNull();

        chatwoot.stubFor(get(ACCOUNT + "/inboxes/5").willReturn(okJson(
                "{\"id\":5,\"working_hours_enabled\":false,\"timezone\":\"Europe/Moscow\",\"working_hours\":[]}")));
        polls.pollHours();
        String always = receiveUntil(ChatQueues.INBOUND, b -> b.contains("working_hours.changed"), 5000).getLast();
        assertThat((String) JsonPath.read(always, "$.days[2].open")).isEqualTo("00:00");
    }

    private void link(long cwId, Instant createdAt) {
        jdbc.update("INSERT INTO chatwoot.chat_message_link (cw_message_id, message_id, user_id, conversation_id, direction, "
                + "cw_created_at) VALUES (?, ?, ?, 15, 'client', ?)", cwId, UUID.randomUUID(), user,
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }
}
