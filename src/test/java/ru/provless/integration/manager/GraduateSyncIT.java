package ru.provless.integration.manager;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import ru.provless.integration.chat.ChatConnectorTestBase;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/** Graduates (front requests S5 §6): Directus «Выпускники» by phone → auth-service → profile-service; WireMock as all three. */
@TestPropertySource(properties = {
        "personal-manager.poll-initial-delay=PT1H",
        "directus.token=test-directus-token"
})
class GraduateSyncIT extends ChatConnectorTestBase {

    static final UUID ROW_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    static final UUID ROW_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");

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
    GraduateSync sync;

    private final UUID anna = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        services.resetAll();
        services.stubFor(patch(urlPathMatching("/directus/items/graduates/.*")).willReturn(okJson("{\"data\":{}}")));
        services.stubFor(put("/internal/profiles/graduates").willReturn(okJson("{\"graduates\":1}")));
        services.stubFor(get(urlPathEqualTo("/internal/users/by-phone")).willReturn(aResponse().withStatus(404)));
        services.stubFor(get(urlPathEqualTo("/internal/users/by-phone")).withQueryParam("phone", equalTo("+79990000001"))
                .willReturn(okJson("{\"user_id\":\"" + anna + "\"}")));
    }

    @Test
    void theAccountsOfThePhonesGoToProfiles_theRowsSayWhoHasOne() {
        services.stubFor(get(urlPathEqualTo("/directus/items/graduates")).willReturn(okJson("""
                {"data":[{"id":"%s","phone":"+79990000001","client":null},
                         {"id":"%s","phone":"+79990000002","client":"нет аккаунта с этим номером"}]}""".formatted(ROW_A, ROW_B))));

        sync.sync();

        services.verify(putRequestedFor(urlEqualTo("/internal/profiles/graduates"))
                .withRequestBody(equalToJson("{\"user_ids\":[\"" + anna + "\"]}")));
        services.verify(patchRequestedFor(urlEqualTo("/directus/items/graduates/" + ROW_A))
                .withRequestBody(equalToJson("{\"client\":\"аккаунт есть\"}")));
        services.verify(0, patchRequestedFor(urlEqualTo("/directus/items/graduates/" + ROW_B)));
    }

    @Test
    void directusDown_nothingCleared() {
        services.stubFor(get(urlPathEqualTo("/directus/items/graduates")).willReturn(aResponse().withStatus(503)));

        sync.sync();

        services.verify(0, putRequestedFor(urlEqualTo("/internal/profiles/graduates")));
    }
}
