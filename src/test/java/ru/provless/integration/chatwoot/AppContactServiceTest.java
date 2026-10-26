package ru.provless.integration.chatwoot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ru.provless.integration.profile.ContactInfo;
import ru.provless.integration.profile.ProfileServiceClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppContactServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String PHONE = "+79001234567";
    private static final long INBOX_ID = 5L;
    private static final Map<String, Object> APP_ATTRS = Map.of("app_user_id", USER_ID.toString());

    @Mock
    private ChatwootClient chatwootClient;
    @Mock
    private ChatwootAppInbox appInbox;
    @Mock
    private AppContactLinkRepository linkRepository;
    @Mock
    private ProfileServiceClient profileServiceClient;

    private AppContactService service;

    @BeforeEach
    void setUp() {
        service = new AppContactService(chatwootClient, appInbox, linkRepository, profileServiceClient);
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @Test
    void createsContactWithAppChannelWhenNobodyHasThePhone() {
        noProfile();
        when(linkRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(appInbox.id()).thenReturn(INBOX_ID);
        when(chatwootClient.findContactByPhone(PHONE)).thenReturn(Optional.empty());
        when(chatwootClient.findContactByIdentifier("app:" + USER_ID)).thenReturn(Optional.empty());
        when(chatwootClient.createContact(PHONE, PHONE, "app:" + USER_ID, APP_ATTRS, INBOX_ID, USER_ID.toString()))
                .thenReturn(contact(42, PHONE));

        assertThat(service.ensure(USER_ID, PHONE)).isEqualTo(42);

        assertLinkSaved(42);
        verify(chatwootClient, never()).addContactInbox(anyLong(), anyLong(), anyString());
    }

    @Test
    void attachesToTheMessengerContactWithTheSamePhone() {
        when(profileServiceClient.findContactInfo(USER_ID)).thenReturn(Optional.of(new ContactInfo(PHONE, "Иван Петров")));
        when(linkRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(appInbox.id()).thenReturn(INBOX_ID);
        when(chatwootClient.findContactByPhone(PHONE)).thenReturn(Optional.of(contact(7, "+79001234567")));

        assertThat(service.ensure(USER_ID, PHONE)).isEqualTo(7);

        // Bridge put the phone as the name — the profile name replaces it.
        verify(chatwootClient).updateContact(7, "Иван Петров", APP_ATTRS);
        verify(chatwootClient).addContactInbox(7, INBOX_ID, USER_ID.toString());
        verify(chatwootClient, never()).createContact(any(), any(), any(), anyMap(), anyLong(), any());
        assertLinkSaved(7);
    }

    @Test
    void keepsTheNameAnAgentTyped() {
        when(profileServiceClient.findContactInfo(USER_ID)).thenReturn(Optional.of(new ContactInfo(PHONE, "Иван Петров")));
        when(linkRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(appInbox.id()).thenReturn(INBOX_ID);
        when(chatwootClient.findContactByPhone(PHONE)).thenReturn(Optional.of(contact(7, "Ваня (клиника на Ленина)")));

        service.ensure(USER_ID, PHONE);

        verify(chatwootClient).updateContact(eq(7L), isNull(), eq(APP_ATTRS));
    }

    @Test
    void raceWithABridgeEndsUpAttachedInsteadOfDuplicated() {
        noProfile();
        when(linkRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(appInbox.id()).thenReturn(INBOX_ID);
        when(chatwootClient.findContactByPhone(PHONE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(contact(9, "Telegram 123456")));
        when(chatwootClient.findContactByIdentifier("app:" + USER_ID)).thenReturn(Optional.empty());
        when(chatwootClient.createContact(any(), any(), any(), anyMap(), anyLong(), any()))
                .thenThrow(new ChatwootException("Phone number has already been taken", 422, null));

        assertThat(service.ensure(USER_ID, PHONE)).isEqualTo(9);

        verify(chatwootClient).addContactInbox(9, INBOX_ID, USER_ID.toString());
        assertLinkSaved(9);
    }

    @Test
    void chatwootDownIsNotMistakenForADuplicate() {
        noProfile();
        when(linkRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(appInbox.id()).thenReturn(INBOX_ID);
        when(chatwootClient.findContactByPhone(PHONE)).thenReturn(Optional.empty());
        when(chatwootClient.findContactByIdentifier("app:" + USER_ID)).thenReturn(Optional.empty());
        when(chatwootClient.createContact(any(), any(), any(), anyMap(), anyLong(), any()))
                .thenThrow(new ChatwootException("Chatwoot POST /contacts failed: 502", 502, null));

        assertThatThrownBy(() -> service.ensure(USER_ID, PHONE)).isInstanceOf(ChatwootException.class);
        verify(linkRepository, never()).save(any());
    }

    @Test
    void repeatedCallIsANoOp() {
        noProfile();
        when(linkRepository.findById(USER_ID)).thenReturn(Optional.of(new AppContactLink(USER_ID, 42)));
        when(chatwootClient.findContact(42)).thenReturn(Optional.of(contact(42, "Иван Петров")));

        assertThat(service.ensure(USER_ID, PHONE)).isEqualTo(42);

        verify(chatwootClient, never()).findContactByPhone(any());
        verify(chatwootClient, never()).updateContact(anyLong(), any(), anyMap());
        verify(linkRepository, never()).save(any());
    }

    @Test
    void contactMergedAwayIsFoundAgainByPhone() {
        noProfile();
        AppContactLink stale = new AppContactLink(USER_ID, 42);
        when(linkRepository.findById(USER_ID)).thenReturn(Optional.of(stale));
        when(chatwootClient.findContact(42)).thenReturn(Optional.empty());
        when(appInbox.id()).thenReturn(INBOX_ID);
        when(chatwootClient.findContactByPhone(PHONE)).thenReturn(Optional.of(contact(43, "Иван")));

        assertThat(service.ensure(USER_ID, PHONE)).isEqualTo(43);

        verify(linkRepository).delete(stale);
        assertLinkSaved(43);
    }

    @Test
    void phoneComesFromTheProfileOnTheLazyPath() {
        when(profileServiceClient.findContactInfo(USER_ID)).thenReturn(Optional.of(new ContactInfo(PHONE, null)));
        when(linkRepository.findById(USER_ID)).thenReturn(Optional.of(new AppContactLink(USER_ID, 42)));
        when(chatwootClient.findContact(42)).thenReturn(Optional.of(contact(42, PHONE)));

        assertThat(service.ensure(USER_ID, null)).isEqualTo(42);
    }

    @Test
    void disabledIntegrationRefusesInsteadOfSilentlySkipping() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertThatThrownBy(() -> service.ensure(USER_ID, PHONE)).isInstanceOf(ChatwootDisabledException.class);
    }

    @Test
    void placeholderNamesFromBridgesAreReplaceable() {
        assertThat(AppContactService.nameToSet(contact(1, "Telegram 123456"), "Иван")).isEqualTo("Иван");
        assertThat(AppContactService.nameToSet(contact(1, "VK id98765"), "Иван")).isEqualTo("Иван");
        assertThat(AppContactService.nameToSet(contact(1, "MAX 555"), "Иван")).isEqualTo("Иван");
        assertThat(AppContactService.nameToSet(contact(1, "79001234567"), "Иван")).isEqualTo("Иван");
        assertThat(AppContactService.nameToSet(contact(1, ""), "Иван")).isEqualTo("Иван");
        assertThat(AppContactService.nameToSet(contact(1, "Telegram Support"), "Иван")).isNull();
        assertThat(AppContactService.nameToSet(contact(1, "Иван"), "Иван")).isNull();
        assertThat(AppContactService.nameToSet(contact(1, "Telegram 123456"), null)).isNull();
    }

    private void noProfile() {
        when(profileServiceClient.findContactInfo(USER_ID)).thenReturn(Optional.empty());
    }

    private void assertLinkSaved(long contactId) {
        ArgumentCaptor<AppContactLink> saved = ArgumentCaptor.forClass(AppContactLink.class);
        verify(linkRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getContactId()).isEqualTo(contactId);
    }

    private static ChatwootContact contact(long id, String name) {
        return new ChatwootContact(id, name, PHONE, null, Map.of());
    }
}
