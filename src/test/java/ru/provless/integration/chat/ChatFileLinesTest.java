package ru.provless.integration.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Files Chatwoot does not take — lines with links under the text. */
class ChatFileLinesTest {

    @Test
    void sizes() {
        assertThat(ChatOutboundHandler.humanSize(8)).isEqualTo("8 Б");
        assertThat(ChatOutboundHandler.humanSize(820 * 1024)).isEqualTo("820 КБ");
        assertThat(ChatOutboundHandler.humanSize(47_500_000)).isEqualTo("45,3 МБ");
        assertThat(ChatOutboundHandler.humanSize(1_288_490_188)).isEqualTo("1,2 ГБ");
        assertThat(ChatOutboundHandler.humanSize(4L * 1024 * 1024 * 1024)).isEqualTo("4 ГБ");
    }

    @Test
    void lines() {
        var a = new OutboundEvent.File(UUID.randomUUID(), "a.mp4", "video/mp4", 50L * 1024 * 1024, "b", "k", "https://x/a");
        var b = new OutboundEvent.File(UUID.randomUUID(), "b.zip", "application/zip", 2048, "b", "k", null);
        assertThat(ChatOutboundHandler.withLinks("вот", List.of())).isEqualTo("вот");
        assertThat(ChatOutboundHandler.withLinks(null, List.of(a, b)))
                .isEqualTo("📎 a.mp4 · 50 МБ: https://x/a\n📎 b.zip · 2 КБ — файл в чате приложения, ссылка не настроена");
        assertThat(ChatOutboundHandler.withLinks("вот", List.of(a))).isEqualTo("вот\n\n📎 a.mp4 · 50 МБ: https://x/a");
    }
}
