package ru.provless.integration.chatwoot;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatwootAppInboxTest {

    private final ChatwootClient client = mock(ChatwootClient.class);

    private ChatwootAppInbox inbox() {
        ChatwootAppInbox inbox = new ChatwootAppInbox(client);
        ReflectionTestUtils.setField(inbox, "inboxName", "Приложение — входящие");
        ReflectionTestUtils.setField(inbox, "configuredInboxId", "");
        ReflectionTestUtils.setField(inbox, "legacyName", "Приложение");
        return inbox;
    }

    @Test
    void oldInbox_renamed_keepsItsHistory() {
        when(client.findInboxIdByName("Приложение — входящие")).thenReturn(Optional.empty());
        when(client.findInboxIdByName("Приложение")).thenReturn(Optional.of(9L));

        assertThat(inbox().id()).isEqualTo(9L);
        verify(client).renameInbox(9L, "Приложение — входящие");
        verify(client, never()).createApiInbox(anyString());
    }

    @Test
    void renamedAlready_usedAsIs() {
        when(client.findInboxIdByName("Приложение — входящие")).thenReturn(Optional.of(9L));

        assertThat(inbox().id()).isEqualTo(9L);
        verify(client, never()).renameInbox(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void none_created() {
        when(client.findInboxIdByName(anyString())).thenReturn(Optional.empty());
        when(client.createApiInbox("Приложение — входящие")).thenReturn(11L);

        assertThat(inbox().id()).isEqualTo(11L);
    }
}
