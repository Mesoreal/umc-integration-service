package ru.provless.integration.chat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import ru.provless.integration.chatwoot.AppContactService;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connector against real Postgres and RabbitMQ (cluster versions), S3Mock for the chat bucket and WireMock in place
 * of Chatwoot. The contact lookup (AppContactService, covered by its own tests) is mocked: it would call profile-service.
 */
@SpringBootTest(properties = {
        "chatwoot.enabled=true",
        "chatwoot.account-id=1",
        "chatwoot.api-token=test-token",
        "chatwoot.app-inbox-id=5",
        "chatwoot.chat.retry-initial=100ms",
        "chatwoot.chat.retry-max=200ms",
        // tests call the polls themselves
        "chatwoot.chat.poll-initial-delay=PT1H",
        // departments of the tests, whatever application.yml names them
        "chatwoot.chat.departments[0].key=sales",
        "chatwoot.chat.departments[0].label=в-продажи",
        "chatwoot.chat.departments[0].inbox-name=Приложение — продажи",
        "chatwoot.chat.departments[1].key=umc",
        "chatwoot.chat.departments[1].label=в-умц",
        "chatwoot.chat.departments[1].inbox-name=Приложение — УМЦ",
        "chatwoot.chat-storage.access-key=k",
        "chatwoot.chat-storage.secret-key=s",
        "chatwoot.chat-storage.bucket=" + ChatConnectorTestBase.BUCKET
})
public abstract class ChatConnectorTestBase {

    static final String BUCKET = "chat-test";
    protected static final String ACCOUNT = "/api/v1/accounts/1";

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9-alpine3.23");

    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4.2.6-management-alpine");

    static GenericContainer<?> s3mock = new GenericContainer<>("adobe/s3mock:4.7.0")
            .withExposedPorts(9090)
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET);

    protected static WireMockServer chatwoot = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        postgres.start();
        rabbitmq.start();
        s3mock.start();
        chatwoot.start();
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("chatwoot.base-url", chatwoot::baseUrl);
        registry.add("chatwoot.chat-storage.endpoint", ChatConnectorTestBase::s3Url);
    }

    @MockitoBean
    AppContactService appContacts;

    @Autowired
    protected RabbitTemplate rabbit;
    @Autowired
    protected JdbcTemplate jdbc;

    /** Every test starts from an empty Chatwoot stub and empty connector tables (the stubs reuse Chatwoot ids). */
    @BeforeEach
    void resetChatwoot() {
        chatwoot.resetAll();
        // the department inboxes (application.yml): «продажи» = 6, «УМЦ» = 7; «Входящие» = 5 (chatwoot.app-inbox-id)
        chatwoot.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(ACCOUNT + "/inboxes").willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.okJson("""
                        {"payload":[{"id":5,"name":"Приложение — входящие"},{"id":6,"name":"Приложение — продажи"},
                                    {"id":7,"name":"Приложение — УМЦ"}]}""")));
        for (int inbox : new int[]{6, 7}) {
            chatwoot.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(ACCOUNT + "/inboxes/" + inbox).willReturn(
                    com.github.tomakehurst.wiremock.client.WireMock.okJson("{\"id\":" + inbox
                            + ",\"inbox_identifier\":\"dept" + inbox + "\",\"secret\":\"secret" + inbox
                            + "\",\"webhook_url\":\"http://integration-service:8080/webhooks/chatwoot/app\","
                            + "\"working_hours_enabled\":false,\"timezone\":\"Europe/Moscow\",\"working_hours\":[]}")));
        }
        jdbc.update("TRUNCATE chatwoot.chat_message_link, chatwoot.chat_conversation, chatwoot.chat_agent, chatwoot.chat_inbox_hours, "
                + "chatwoot.chat_transfer_copy");
    }

    static String s3Url() {
        return "http://" + s3mock.getHost() + ":" + s3mock.getMappedPort(9090);
    }

    static void putFile(String key, byte[] content) {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(s3Url()))
                .region(Region.of("ru-central1"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("k", "s")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            s3.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromBytes(content));
        }
    }

    protected void outbound(String json) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send("", ChatQueues.OUTBOUND, new Message(json.getBytes(StandardCharsets.UTF_8), props));
    }

    /** Messages of {@code queue} until one matches; the others are returned too, in order (assert on what came). */
    protected List<String> receiveUntil(String queue, Predicate<String> last, long timeoutMs) {
        List<String> got = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Message m = rabbit.receive(queue, Math.max(1, deadline - System.currentTimeMillis()));
            if (m == null) {
                break;
            }
            String body = new String(m.getBody(), StandardCharsets.UTF_8);
            got.add(body);
            if (last.test(body)) {
                return got;
            }
        }
        assertThat(got).as("expected message on " + queue).anyMatch(last);
        return got;
    }

    protected void drain(String queue) {
        while (rabbit.receive(queue, 200) != null) {
            // left from an earlier test
        }
    }
}
